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
import org.example.springtestci.product.repository.ProductRepository;
import org.example.springtestci.product.repository.ProductRepository.ProductImageRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 노트북 등록·조회·수정과 이미지 관리. 모든 조회·변경은 현재 소유자에게만 허용한다. */
@Service
public class ProductService {

  static final int MAX_IMAGES_PER_PRODUCT = 10;

  private static final Logger log = LoggerFactory.getLogger(ProductService.class);

  private final ProductRepository productRepository;
  private final SerialNumberProtector serialNumberProtector;
  private final ProductImageValidator imageValidator;
  private final ObjectProvider<FileStorage> fileStorage;
  private final ObjectMapper objectMapper;

  /** 제품 저장소, 보안, 파일 저장소 의존성으로 서비스를 구성한다. */
  public ProductService(
      ProductRepository productRepository,
      SerialNumberProtector serialNumberProtector,
      ProductImageValidator imageValidator,
      ObjectProvider<FileStorage> fileStorage,
      ObjectMapper objectMapper) {
    this.productRepository = productRepository;
    this.serialNumberProtector = serialNumberProtector;
    this.imageValidator = imageValidator;
    this.fileStorage = fileStorage;
    this.objectMapper = objectMapper;
  }

  /** 제품과 노트북 사양, 필드 작성자 정보를 등록한다. */
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

  /** 현재 소유자의 제품 목록에 이미지 정보를 결합해 반환한다. */
  @Transactional(readOnly = true)
  public List<ProductResponse> listMine(long ownerId) {
    return productRepository.findAllOwned(ownerId).stream().map(this::withImages).toList();
  }

  /** 소유권을 확인하고 제품 상세, 이미지, 복호화된 일련번호를 반환한다. */
  @Transactional(readOnly = true)
  public ProductResponse get(long ownerId, long productId) {
    ProductResponse product =
        productRepository
            .findOwned(productId, ownerId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    String ciphertext =
        productRepository
            .findSerialCiphertextOwned(productId, ownerId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    return withImages(product).withSerialNumber(serialNumberProtector.decrypt(ciphertext));
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

    requireFieldAuthor(productId, ownerId, "TITLE", before.title(), request.title());
    requireFieldAuthor(
        productId, ownerId, "PURCHASE_DATE", before.purchaseDate(), request.purchaseDate());
    requireFieldAuthor(
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
    return withImages(after)
        .withSerialNumber(
            serialNumberProtector.decrypt(
                productRepository.findSerialCiphertextOwned(productId, ownerId).orElseThrow()));
  }

  /** 검증된 이미지를 저장하고 제품 이미지 메타데이터에 추가한다. */
  @Transactional
  public ProductResponse addImage(
      long ownerId, long productId, String filename, String contentType, byte[] content) {
    requireOwnedLock(ownerId, productId);
    ProductImageValidator.ImageKind kind = imageValidator.validate(filename, contentType, content);
    if (productRepository.countImages(productId) >= MAX_IMAGES_PER_PRODUCT) {
      throw new CommonException(ErrorCode.PRODUCT_IMAGE_LIMIT_EXCEEDED);
    }

    String storageKey = store(productId, kind.extension(), content);
    try {
      productRepository.insertImage(productId, storageKey, kind.contentType(), content.length);
    } catch (RuntimeException exception) {
      deleteStoredFile(storageKey, true);
      throw exception;
    }
    return get(ownerId, productId);
  }

  /** 전체 이미지 집합을 검증한 뒤 순서와 대표 이미지를 갱신한다. */
  @Transactional
  public ProductResponse updateImages(
      long ownerId, long productId, UpdateProductImagesRequest request) {
    requireOwnedLock(ownerId, productId);
    List<Long> current =
        productRepository.findImages(productId).stream().map(ProductImageRecord::imageId).toList();
    Set<Long> requested = new HashSet<>(request.imageIds());
    if (requested.size() != request.imageIds().size()
        || !requested.equals(new HashSet<>(current))
        || !requested.contains(request.primaryImageId())) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    productRepository.replaceImageOrder(productId, request.imageIds(), request.primaryImageId());
    return get(ownerId, productId);
  }

  /** 소유 제품의 이미지를 삭제하고 저장 파일 정리를 시도한다. */
  @Transactional
  public ProductResponse deleteImage(long ownerId, long productId, long imageId) {
    requireOwnedLock(ownerId, productId);
    ProductImageRecord image =
        productRepository
            .findImage(productId, imageId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    productRepository.softDeleteImage(productId, imageId);
    deleteStoredFile(image.storageKey(), true);
    return get(ownerId, productId);
  }

  /** 소유권과 파일 상태를 확인한 뒤 이미지 리소스를 반환한다. */
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

  /** 제품 소유권을 검증하면서 변경용 행 잠금을 획득한다. */
  private void requireOwnedLock(long ownerId, long productId) {
    if (!productRepository.lockOwned(productId, ownerId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  /** 변경된 필드의 최초 작성자가 현재 소유자인지 확인한다. */
  private void requireFieldAuthor(
      long productId, long ownerId, String fieldName, Object before, Object after) {
    if (Objects.equals(before, after)) return;
    OptionalLongAuthor author =
        productRepository
            .findFieldAuthor(productId, fieldName)
            .map(OptionalLongAuthor::known)
            .orElseGet(OptionalLongAuthor::missing);
    if (author.known() && author.authorId() != ownerId) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
    if (!author.known()) productRepository.insertFieldAuthor(productId, fieldName, ownerId);
  }

  /** 감사 로그에 저장할 제품 사실 스냅샷을 JSON으로 직렬화한다. */
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

  /** 구매일이 보증 만료일보다 늦지 않은지 검증한다. */
  private static void validateDates(
      java.time.LocalDate purchaseDate, java.time.LocalDate warrantyExpiresAt) {
    if (purchaseDate != null
        && warrantyExpiresAt != null
        && warrantyExpiresAt.isBefore(purchaseDate)) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
  }

  /** 저장된 이미지 메타데이터를 API 이미지 응답으로 변환해 제품에 결합한다. */
  private ProductResponse withImages(ProductResponse product) {
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

  /** 인증된 제품 이미지 조회 URL을 생성한다. */
  private static String imageUrl(long productId, long imageId) {
    return "/api/v1/products/" + productId + "/images/" + imageId;
  }

  /** 구성된 파일 저장소를 반환하거나 사용 불가 오류를 발생시킨다. */
  private FileStorage storage() {
    FileStorage storage = fileStorage.getIfAvailable();
    if (storage == null) throw new CommonException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
    return storage;
  }

  /** 제품별 경로에 이미지 바이트를 저장하고 저장 키를 반환한다. */
  private String store(long productId, String extension, byte[] content) {
    try {
      return storage()
          .store("product-images/" + productId, extension, new ByteArrayInputStream(content));
    } catch (IOException exception) {
      log.error("Failed to store product image productId={}", productId);
      throw new CommonException(ErrorCode.INTERNAL_ERROR);
    }
  }

  /** 저장 파일을 삭제하고 정책에 따라 정리 실패를 억제한다. */
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

  private record OptionalLongAuthor(boolean known, long authorId) {
    /** 조회된 작성자 ID를 포함한 값을 만든다. */
    static OptionalLongAuthor known(long authorId) {
      return new OptionalLongAuthor(true, authorId);
    }

    /** 작성자가 기록되지 않았음을 나타내는 값을 만든다. */
    static OptionalLongAuthor missing() {
      return new OptionalLongAuthor(false, 0);
    }
  }

  /** 제품 수정 감사 로그에 직렬화할 사실만 보관한다. */
  private record AuditFacts(
      String title,
      java.time.LocalDate purchaseDate,
      java.time.LocalDate warrantyExpiresAt,
      String warrantyTerms,
      long factsVersion) {}

  /** 이미지 리소스와 신뢰할 수 있는 콘텐츠 타입을 함께 반환한다. */
  public record ProductImageContent(Resource resource, String contentType) {}
}
