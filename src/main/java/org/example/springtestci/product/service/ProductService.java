package org.example.springtestci.product.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.storage.FileStorage;
import org.example.springtestci.product.dto.CreateProductRequest;
import org.example.springtestci.product.dto.ProductResponse;
import org.example.springtestci.product.dto.UpdateProductImagesRequest;
import org.example.springtestci.product.dto.UpdateProductRequest;
import org.example.springtestci.product.event.ProductFactsChanged;
import org.example.springtestci.product.repository.ProductRepository;
import org.example.springtestci.product.repository.ProductRepository.ProductImageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 노트북 등록·조회·수정과 이미지 관리. 모든 조회·변경은 현재 소유자에게만 허용한다. */
@Service
public class ProductService {

  // 한 제품에 저장할 수 있는 이미지 수를 한곳에서 관리하기 위한 업무 규칙 상수다.
  static final int MAX_IMAGES_PER_PRODUCT = 10;

  private static final Logger log = LoggerFactory.getLogger(ProductService.class);

  private final ProductRepository productRepository;
  private final SerialNumberProtector serialNumberProtector;
  private final ProductImageValidator imageValidator;
  private final ObjectProvider<FileStorage> fileStorage;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher events;

  public ProductService(
      ProductRepository productRepository,
      SerialNumberProtector serialNumberProtector,
      ProductImageValidator imageValidator,
      ObjectProvider<FileStorage> fileStorage,
      ObjectMapper objectMapper,
      ApplicationEventPublisher events) {
    this.productRepository = productRepository;
    this.serialNumberProtector = serialNumberProtector;
    this.imageValidator = imageValidator;
    this.fileStorage = fileStorage;
    this.objectMapper = objectMapper;
    this.events = events;
  }

  /** 제품·노트북 상세·필드 작성자를 하나의 트랜잭션에서 저장한다. */
  @Transactional
  public ProductResponse create(long ownerId, CreateProductRequest request) {
    validateDates(request.purchaseDate(), request.warrantyExpiresAt());
    String manufacturerNormalized =
        SerialNumberProtector.normalizeManufacturer(request.manufacturer());
    String fingerprint = serialNumberProtector.fingerprint(request.serialNumber());
    if (productRepository.existsByManufacturerAndSerial(manufacturerNormalized, fingerprint)) {
      throw new CommonException(ErrorCode.PRODUCT_DUPLICATE_SERIAL);
    }
    long productId;
    try {
      productId =
          productRepository.insertProduct(
              ownerId,
              request,
              manufacturerNormalized,
              fingerprint,
              serialNumberProtector.encrypt(request.serialNumber()));
    } catch (DataIntegrityViolationException exception) {
      throw new CommonException(ErrorCode.PRODUCT_DUPLICATE_SERIAL);
    }
    productRepository.insertLaptopDetails(productId, request.specifications());
    productRepository.insertFieldAuthors(productId, ownerId, request);
    return get(ownerId, productId);
  }

  /** 소유 제품 목록을 조회하고 각 제품의 이미지 정보를 결합한다. */
  @Transactional(readOnly = true)
  public List<ProductResponse> listMine(long ownerId) {
    return productRepository.findAllOwned(ownerId).stream().map(this::withImages).toList();
  }

  /** 소유권을 확인한 뒤 암호화된 일련번호를 복호화해 상세 응답에 포함한다. */
  @Transactional(readOnly = true)
  public ProductResponse get(long ownerId, long productId) {
    ProductResponse product = getForListing(ownerId, productId);
    String ciphertext =
        productRepository
            .findSerialCiphertextOwned(productId, ownerId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    return product.withSerialNumber(serialNumberProtector.decrypt(ciphertext));
  }

  /** 판매 기능은 시리얼 조회·복호화 없이 제품 정보·사양·사진을 받는다. */
  @Transactional(readOnly = true)
  public ProductResponse getForListing(long ownerId, long productId) {
    return productRepository
        .findOwned(productId, ownerId)
        .map(this::withImages)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  /** 판매 초안 저장도 제품 변경과 같은 행 잠금을 사용한다. 호출 측 트랜잭션에 참여한다. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockOwnedForListing(long ownerId, long productId) {
    requireOwnedLock(ownerId, productId);
  }

  /** PROD-08에 정의된 기본 정보만 수정하고 변경 전후 값과 사유를 감사 로그에 남긴다. */
  @Transactional
  public ProductResponse update(long ownerId, long productId, UpdateProductRequest request) {
    validateDates(request.purchaseDate(), request.warrantyExpiresAt());
    if (!productRepository.lockOwned(productId, ownerId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    ProductResponse before =
        productRepository
            .findOwned(productId, ownerId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));

    replaceFieldAuthor(productId, ownerId, "TITLE", before.title(), request.title());
    replaceFieldAuthor(
        productId, ownerId, "PURCHASE_DATE", before.purchaseDate(), request.purchaseDate());
    replaceFieldAuthor(
        productId,
        ownerId,
        "WARRANTY_EXPIRES_AT",
        before.warrantyExpiresAt(),
        request.warrantyExpiresAt());
    productRepository.updateProduct(productId, request);
    ProductResponse after =
        productRepository
            .findOwned(productId, ownerId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    productRepository.insertProductAudit(
        productId, ownerId, auditJson(before), auditJson(after), request.reason());
    events.publishEvent(new ProductFactsChanged(productId));
    return withImages(after)
        .withSerialNumber(
            serialNumberProtector.decrypt(
                productRepository.findSerialCiphertextOwned(productId, ownerId).orElseThrow()));
  }

  /** 현재 소유자만 삭제(보관)할 수 있다. 판매 중·예약 중인 판매글이 있으면 막고, 기록은 지우지 않는다. */
  @Transactional
  public void delete(long ownerId, long productId) {
    requireOwnedLock(ownerId, productId);
    if (productRepository.hasOpenListing(productId))
      throw new CommonException(ErrorCode.PRODUCT_DELETE_BLOCKED);
    productRepository.archive(productId);
  }

  @Transactional
  public ProductResponse addImage(
      long ownerId, long productId, String filename, String contentType, byte[] content) {
    // 같은 제품의 동시 이미지 추가가 최대 개수 검사를 통과하지 못하도록 제품 행을 먼저 잠근다.
    requireOwnedLock(ownerId, productId);
    ProductImageValidator.ImageKind kind = imageValidator.validate(filename, contentType, content);
    if (productRepository.countImages(productId) >= MAX_IMAGES_PER_PRODUCT) {
      throw new CommonException(ErrorCode.PRODUCT_IMAGE_LIMIT_EXCEEDED);
    }

    // DB에는 파일 자체가 아니라 파일 저장소에서 찾을 때 사용하는 storageKey를 보관한다.
    String storageKey = store(productId, kind.extension(), content);
    try {
      productRepository.insertImage(productId, storageKey, kind.contentType(), content.length);
    } catch (RuntimeException exception) {
      deleteStoredFile(storageKey, true);
      throw exception;
    }
    return get(ownerId, productId);
  }

  @Transactional
  public ProductResponse updateImages(
      long ownerId, long productId, UpdateProductImagesRequest request) {
    requireOwnedLock(ownerId, productId);
    List<Long> current =
        productRepository.findImages(productId).stream().map(ProductImageRecord::imageId).toList();
    // Set은 중복을 제거하고 현재 이미지 ID 집합과 요청 ID 집합이 같은지 비교하기 위해 사용한다.
    Set<Long> requested = new HashSet<>(request.imageIds());
    if (requested.size() != request.imageIds().size()
        || !requested.equals(new HashSet<>(current))
        || !requested.contains(request.primaryImageId())) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    productRepository.replaceImageOrder(productId, request.imageIds(), request.primaryImageId());
    return get(ownerId, productId);
  }

  @Transactional
  public ProductResponse deleteImage(long ownerId, long productId, long imageId) {
    requireOwnedLock(ownerId, productId);
    if (productRepository.isPublishedImage(productId, imageId))
      throw new CommonException(ErrorCode.PRODUCT_IMAGE_IN_USE);
    ProductImageRecord image =
        productRepository
            .findImage(productId, imageId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    productRepository.softDeleteImage(productId, imageId);
    deleteStoredFile(image.storageKey(), true);
    return get(ownerId, productId);
  }

  @Transactional(readOnly = true)
  public ProductImageContent image(long ownerId, long productId, long imageId) {
    if (productRepository.findOwned(productId, ownerId).isEmpty()) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    ProductImageRecord image =
        productRepository
            .findImage(productId, imageId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    try {
      Resource resource = storage().load(image.storageKey());
      if (!resource.exists() || !resource.isReadable()) {
        throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
      }
      return new ProductImageContent(resource, image.contentType());
    } catch (IOException exception) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  private void requireOwnedLock(long ownerId, long productId) {
    if (!productRepository.lockOwned(productId, ownerId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  private void replaceFieldAuthor(
      long productId, long ownerId, String fieldName, Object before, Object after) {
    if (Objects.equals(before, after)) return;
    productRepository.replaceFieldAuthor(productId, fieldName, ownerId);
  }

  private String auditJson(ProductResponse product) {
    try {
      return objectMapper.writeValueAsString(
          new AuditFacts(
              product.title(),
              product.purchaseDate(),
              product.warrantyExpiresAt(),
              product.warrantyTerms(),
              product.factsVersion()));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Failed to serialize product audit facts", exception);
    }
  }

  private static void validateDates(
      java.time.LocalDate purchaseDate, java.time.LocalDate warrantyExpiresAt) {
    if (purchaseDate != null
        && warrantyExpiresAt != null
        && warrantyExpiresAt.isBefore(purchaseDate)) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
  }

  private ProductResponse withImages(ProductResponse product) {
    // DB의 이미지 레코드를 API 응답 전용 Image DTO로 변환한다.
    List<ProductResponse.Image> images =
        productRepository.findImages(product.productId()).stream()
            .map(
                image ->
                    new ProductResponse.Image(
                        image.imageId(),
                        imageUrl(product.productId(), image.imageId()),
                        image.primary(),
                        image.sortOrder(),
                        image.byteSize()))
            .toList();
    return product.withImages(images);
  }

  private static String imageUrl(long productId, long imageId) {
    return "/api/v1/products/" + productId + "/images/" + imageId;
  }

  private FileStorage storage() {
    FileStorage storage = fileStorage.getIfAvailable();
    if (storage == null) throw new CommonException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
    return storage;
  }

  private String store(long productId, String extension, byte[] content) {
    try {
      return storage()
          .store("product-images/" + productId, extension, new ByteArrayInputStream(content));
    } catch (IOException exception) {
      log.error("Failed to store product image productId={}", productId);
      throw new CommonException(ErrorCode.INTERNAL_ERROR);
    }
  }

  private void deleteStoredFile(String storageKey, boolean suppressFailure) {
    try {
      storage().delete(storageKey);
    } catch (IOException | RuntimeException exception) {
      if (suppressFailure) {
        log.warn("Failed to clean up product image storageKey={}", storageKey);
        return;
      }
      throw new CommonException(ErrorCode.INTERNAL_ERROR);
    }
  }

  private record AuditFacts(
      String title,
      java.time.LocalDate purchaseDate,
      java.time.LocalDate warrantyExpiresAt,
      String warrantyTerms,
      long factsVersion) {}

  public record ProductImageContent(Resource resource, String contentType) {}
}
