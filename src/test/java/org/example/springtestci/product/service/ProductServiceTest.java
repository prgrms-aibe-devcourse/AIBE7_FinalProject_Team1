package org.example.springtestci.product.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.storage.FileStorage;
import org.example.springtestci.product.dto.CreateProductRequest;
import org.example.springtestci.product.dto.ProductResponse;
import org.example.springtestci.product.dto.UpdateProductImagesRequest;
import org.example.springtestci.product.dto.UpdateProductRequest;
import org.example.springtestci.product.repository.ProductRepository;
import org.example.springtestci.product.repository.ProductRepository.ProductImageRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  private static final long OWNER = 1L;
  private static final long PRODUCT = 100L;
  private static final String CIPHERTEXT_SECRET = "AB12CD34";
  private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

  @Mock private ProductRepository productRepository;
  @Mock private FileStorage fileStorage;
  @Mock private ObjectProvider<FileStorage> storageProvider;

  private SerialNumberProtector protector;
  private ProductService service;

  @BeforeEach
  void setUp() {
    protector = new SerialNumberProtector("unit-test-product-serial-secret-32-bytes!!");
    service =
        new ProductService(
            productRepository,
            protector,
            new ProductImageValidator(),
            storageProvider,
            JsonMapper.builder().build(),
            mock(org.springframework.context.ApplicationEventPublisher.class));
  }

  @Test
  void createUsesNormalizedManufacturerAndSerialAsTheDuplicateKey() {
    CreateProductRequest request = request("ab12 cd34");
    when(productRepository.existsByManufacturerAndSerial(
            "APPLE", protector.fingerprint("AB12CD34")))
        .thenReturn(false);
    when(productRepository.insertProduct(
            eq(OWNER), eq(request), eq("APPLE"), anyString(), anyString()))
        .thenReturn(PRODUCT);
    stubGet();

    ProductResponse response = service.create(OWNER, request);

    assertEquals(PRODUCT, response.productId());
    verify(productRepository).insertFieldAuthors(PRODUCT, OWNER, request);
    verify(productRepository).insertLaptopDetails(PRODUCT, request.specifications());
  }

  @Test
  void sameSerialCanBeRegisteredForAnotherManufacturer() {
    CreateProductRequest request = request("AB12CD34");
    when(productRepository.existsByManufacturerAndSerial(
            "APPLE", protector.fingerprint("AB12CD34")))
        .thenReturn(false);
    when(productRepository.insertProduct(anyLong(), any(), anyString(), anyString(), anyString()))
        .thenReturn(PRODUCT);
    stubGet();

    service.create(OWNER, request);

    verify(productRepository)
        .existsByManufacturerAndSerial("APPLE", protector.fingerprint("AB12CD34"));
  }

  @Test
  void createMapsAConcurrentUniqueViolationToADuplicateError() {
    when(productRepository.existsByManufacturerAndSerial(anyString(), anyString()))
        .thenReturn(false);
    when(productRepository.insertProduct(anyLong(), any(), anyString(), anyString(), anyString()))
        .thenThrow(new DuplicateKeyException("uk_products_manufacturer_serial"));

    assertCode(
        ErrorCode.PRODUCT_DUPLICATE_SERIAL, () -> service.create(OWNER, request("AB12CD34")));

    verify(productRepository, never()).insertLaptopDetails(anyLong(), any());
  }

  @Test
  void detailReturnsDecryptedSerialOnlyAfterOwnershipCheck() {
    stubGet();

    ProductResponse response = service.get(OWNER, PRODUCT);

    assertEquals(CIPHERTEXT_SECRET, response.serialNumber());
  }

  @Test
  void listUsesBoundedPagingAndBatchImageLookup() {
    when(productRepository.findOwnedPage(OWNER, 0, 20)).thenReturn(List.of(product()));
    when(productRepository.findImages(List.of(PRODUCT)))
        .thenReturn(Map.of(PRODUCT, List.of(image(1L, true))));
    when(productRepository.summarizeOwned(OWNER))
        .thenReturn(new ProductRepository.OwnedProductSummary(21L, 8L));

    var response = service.listMine(OWNER, 0, 20);

    assertEquals(1, response.items().size());
    assertEquals(1, response.items().get(0).images().size());
    assertEquals(21L, response.page().totalElements());
    assertEquals(true, response.page().hasNext());
    assertEquals(8L, response.summary().purchased());
    verify(productRepository).findImages(List.of(PRODUCT));
    verify(productRepository, never()).findImages(PRODUCT);
  }

  @Test
  void listRejectsAnUnboundedPageSize() {
    assertCode(ErrorCode.INVALID_INPUT, () -> service.listMine(OWNER, 0, 101));
    verify(productRepository, never()).findOwnedPage(anyLong(), anyInt(), anyInt());
  }

  @Test
  void updateChangesOfficialFieldsAndStoresAnAuditRecord() {
    UpdateProductRequest request = updateRequest();
    ProductResponse after = product(2L, request.title(), null);
    when(productRepository.lockOwned(PRODUCT, OWNER)).thenReturn(true);
    when(productRepository.findOwned(PRODUCT, OWNER))
        .thenReturn(Optional.of(product()), Optional.of(after));
    when(productRepository.findSerialCiphertextOwned(PRODUCT, OWNER))
        .thenReturn(Optional.of(protector.encrypt(CIPHERTEXT_SECRET)));
    when(productRepository.findImages(PRODUCT)).thenReturn(List.of());

    ProductResponse response = service.update(OWNER, PRODUCT, request);

    assertEquals("업무용 맥북", response.title());
    assertEquals(2L, response.factsVersion());
    verify(productRepository).updateProduct(PRODUCT, request);
    verify(productRepository)
        .insertProductAudit(eq(PRODUCT), eq(OWNER), anyString(), anyString(), eq("구매 증빙 확인"));
  }

  @Test
  void currentOwnerCanUpdateAFieldWrittenByAPreviousOwner() {
    UpdateProductRequest request = updateRequest();
    ProductResponse after = product(2L, request.title(), null);
    when(productRepository.lockOwned(PRODUCT, OWNER)).thenReturn(true);
    when(productRepository.findOwned(PRODUCT, OWNER))
        .thenReturn(Optional.of(product()), Optional.of(after));
    when(productRepository.findSerialCiphertextOwned(PRODUCT, OWNER))
        .thenReturn(Optional.of(protector.encrypt(CIPHERTEXT_SECRET)));
    when(productRepository.findImages(PRODUCT)).thenReturn(List.of());

    ProductResponse response = service.update(OWNER, PRODUCT, request);

    assertEquals(request.title(), response.title());
    verify(productRepository).replaceFieldAuthor(PRODUCT, "TITLE", OWNER);
    verify(productRepository).replaceFieldAuthor(PRODUCT, "WARRANTY_EXPIRES_AT", OWNER);
    verify(productRepository).updateProduct(PRODUCT, request);
  }

  @Test
  void updateForAnotherOwnersProductIsNotFound() {
    when(productRepository.lockOwned(PRODUCT, 2L)).thenReturn(false);

    assertCode(ErrorCode.RESOURCE_NOT_FOUND, () -> service.update(2L, PRODUCT, updateRequest()));
  }

  @Test
  void addImageStoresByteSizeAndFirstImageMetadata() throws IOException {
    when(productRepository.lockOwned(PRODUCT, OWNER)).thenReturn(true);
    when(productRepository.countImages(PRODUCT)).thenReturn(0);
    when(storageProvider.getIfAvailable()).thenReturn(fileStorage);
    when(fileStorage.store(eq("product-images/100"), eq("png"), any(InputStream.class)))
        .thenReturn("product-images/100/key.png");
    stubGet();

    service.addImage(OWNER, PRODUCT, "a.png", "image/png", PNG);

    verify(productRepository)
        .insertImage(PRODUCT, "product-images/100/key.png", "image/png", PNG.length);
  }

  @Test
  void updateImagesRequiresTheCompleteOwnedImageSet() {
    when(productRepository.lockOwned(PRODUCT, OWNER)).thenReturn(true);
    when(productRepository.findImages(PRODUCT))
        .thenReturn(List.of(image(1L, true), image(2L, false)));

    assertCode(
        ErrorCode.INVALID_INPUT,
        () ->
            service.updateImages(OWNER, PRODUCT, new UpdateProductImagesRequest(List.of(1L), 1L)));

    verify(productRepository, never()).replaceImageOrder(anyLong(), any(), anyLong());
  }

  @Test
  void deleteImageRemovesStoredFileAndDatabaseRecord() throws IOException {
    when(productRepository.lockOwned(PRODUCT, OWNER)).thenReturn(true);
    when(productRepository.findImage(PRODUCT, 1L)).thenReturn(Optional.of(image(1L, true)));
    when(storageProvider.getIfAvailable()).thenReturn(fileStorage);
    stubGet();

    service.deleteImage(OWNER, PRODUCT, 1L);

    verify(fileStorage).delete("product-images/100/1.png");
    verify(productRepository).softDeleteImage(PRODUCT, 1L);
  }

  @Test
  void deleteArchivesOwnedProductWithoutOpenListing() {
    when(productRepository.lockOwned(PRODUCT, OWNER)).thenReturn(true);

    service.delete(OWNER, PRODUCT);

    verify(productRepository).archive(PRODUCT);
  }

  @Test
  void deleteIsRejectedForOtherOwnerOrOpenListing() {
    when(productRepository.lockOwned(PRODUCT, 999L)).thenReturn(false);
    assertThrows(CommonException.class, () -> service.delete(999L, PRODUCT));

    when(productRepository.lockOwned(PRODUCT, OWNER)).thenReturn(true);
    when(productRepository.hasOpenListing(PRODUCT)).thenReturn(true);
    assertThrows(CommonException.class, () -> service.delete(OWNER, PRODUCT));

    verify(productRepository, never()).archive(anyLong());
  }

  @Test
  void imageReadRequiresOwnershipAndReturnsNoSniffCompatibleContent() throws IOException {
    when(productRepository.findOwned(PRODUCT, OWNER)).thenReturn(Optional.of(product()));
    when(productRepository.findImage(PRODUCT, 1L)).thenReturn(Optional.of(image(1L, true)));
    when(storageProvider.getIfAvailable()).thenReturn(fileStorage);
    when(fileStorage.load("product-images/100/1.png")).thenReturn(new ByteArrayResource(PNG));

    var image = service.image(OWNER, PRODUCT, 1L);

    assertEquals("image/png", image.contentType());
  }

  private void stubGet() {
    when(productRepository.findOwned(PRODUCT, OWNER)).thenReturn(Optional.of(product()));
    when(productRepository.findSerialCiphertextOwned(PRODUCT, OWNER))
        .thenReturn(Optional.of(protector.encrypt(CIPHERTEXT_SECRET)));
    when(productRepository.findImages(PRODUCT)).thenReturn(List.of());
  }

  private static CreateProductRequest request(String serialNumber) {
    return new CreateProductRequest(
        "MacBook Pro 14",
        " Apple ",
        "MacBook Pro 14",
        null,
        null,
        null,
        serialNumber,
        new CreateProductRequest.Specifications(
            "Apple M3 Pro", null, 18, 512, "SSD", "14인치", 92, "macOS"));
  }

  private static UpdateProductRequest updateRequest() {
    return new UpdateProductRequest("업무용 맥북", null, LocalDate.of(2027, 1, 2), "구매 증빙 확인");
  }

  private static ProductResponse product() {
    return product(1L, "MacBook Pro 14", null);
  }

  private static ProductResponse product(long factsVersion, String title, String warrantyTerms) {
    return new ProductResponse(
        PRODUCT,
        title,
        "Apple",
        "MacBook Pro 14",
        null,
        null,
        warrantyTerms,
        factsVersion,
        null,
        "UNKNOWN",
        new ProductResponse.Specifications(null, null, null, null, null, null, null, null),
        List.of(),
        OffsetDateTime.parse("2026-10-15T00:00:00Z"));
  }

  private static ProductImageRecord image(long id, boolean primary) {
    return new ProductImageRecord(
        id, "product-images/100/" + id + ".png", "image/png", 100L, (int) id - 1, primary);
  }

  private static void assertCode(ErrorCode expected, Runnable action) {
    CommonException exception = assertThrows(CommonException.class, action::run);
    assertEquals(expected, exception.errorCode());
  }
}
