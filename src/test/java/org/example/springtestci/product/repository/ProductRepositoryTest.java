package org.example.springtestci.product.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.example.springtestci.product.dto.CreateProductRequest;
import org.example.springtestci.product.dto.ProductResponse;
import org.example.springtestci.product.dto.UpdateProductRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 전체 Flyway 마이그레이션을 빈 DB에 적용하고 제품 SQL과 제약을 검증한다. */
class ProductRepositoryTest {

  private static final String URL =
      "jdbc:h2:mem:product_repository;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

  private static ProductRepository repository;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "sa", "", true);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    repository = new ProductRepository(jdbc);
  }

  @Test
  void createdProductIsReadBackWithRequirementsFieldsAndAuthors() {
    long productId = insert(1L, "APPLE", "fp-read-back");

    ProductResponse product = repository.findOwned(productId, 1L).orElseThrow();

    assertEquals("MacBook Pro 14", product.title());
    assertEquals("Apple M3 Pro", product.specifications().cpu());
    assertEquals("구매 후 1년", product.warrantyTerms());
    assertEquals(1L, product.factsVersion());
    assertEquals(1L, repository.findFieldAuthor(productId, "TITLE").orElseThrow());
  }

  @Test
  void ownedProductCursorIndexIsCreatedByFlyway() {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.indexes WHERE LOWER(index_name) = 'ix_products_owner_archive_created'",
            Integer.class);

    assertEquals(1, count);
  }

  @Test
  void ownedProductIncludesHistoryCountAndMostRecentRecord() {
    long productId = insert(21L, "APPLE", "fp-history-summary");
    jdbc.update(
        "INSERT INTO product_events (product_id, author_id, event_type, occurred_on, title) VALUES (?, ?, 'INSPECTION', ?, ?)",
        productId,
        21L,
        LocalDate.of(2026, 10, 1),
        "초기 점검");
    jdbc.update(
        "INSERT INTO product_events (product_id, author_id, event_type, occurred_on, title) VALUES (?, ?, 'REPAIR', ?, ?)",
        productId,
        21L,
        LocalDate.of(2026, 10, 2),
        "키보드 수리");

    ProductResponse product = repository.findOwned(productId, 21L).orElseThrow();

    assertEquals(2L, product.historyCount());
    assertEquals("REPAIR", product.recentHistory().eventType());
    assertEquals("키보드 수리", product.recentHistory().title());
    assertEquals(LocalDate.of(2026, 10, 2), product.recentHistory().occurredOn());
  }

  @Test
  void ownedProductsArePagedInStableOrderAndImagesAreLoadedInOneBatch() {
    long oldest = insert(24L, "APPLE", "fp-page-oldest");
    long middle = insert(24L, "APPLE", "fp-page-middle", LocalDate.of(2026, 10, 20));
    long newest = insert(24L, "APPLE", "fp-page-newest");
    repository.insertImage(oldest, "product-images/page-oldest.png", "image/png", 10L);
    repository.insertImage(middle, "product-images/page-middle.png", "image/png", 30L);
    repository.insertImage(newest, "product-images/page-newest.png", "image/png", 20L);

    var firstPage = repository.findOwnedPage(24L, null, null, 2);
    repository.archive(newest);
    var secondPage =
        repository.findOwnedPage(
            24L, firstPage.get(1).createdAt(), firstPage.get(1).productId(), 2);
    var images = repository.findImages(firstPage.stream().map(ProductResponse::productId).toList());
    var summary = repository.summarizeOwned(24L);

    assertEquals(
        List.of(newest, middle), firstPage.stream().map(ProductResponse::productId).toList());
    assertEquals(List.of(oldest), secondPage.stream().map(ProductResponse::productId).toList());
    assertEquals(1, images.get(newest).size());
    assertEquals(1, images.get(middle).size());
    assertEquals(2L, summary.total());
    assertEquals(1L, summary.purchased());
  }

  @Test
  void replacingFieldAuthorTransfersEditResponsibilityToCurrentOwner() {
    long productId = insert(22L, "APPLE", "fp-field-author");

    repository.replaceFieldAuthor(productId, "TITLE", 23L);

    assertEquals(23L, repository.findFieldAuthor(productId, "TITLE").orElseThrow());
  }

  @Test
  void anotherUserCannotReadOrLockTheProduct() {
    long productId = insert(2L, "APPLE", "fp-other-user");

    assertTrue(repository.findOwned(productId, 99L).isEmpty());
    assertFalse(repository.lockOwned(productId, 99L));
    assertTrue(repository.lockOwned(productId, 2L));
  }

  @Test
  void duplicateRuleUsesManufacturerAndSerialTogether() {
    insert(3L, "APPLE", "fp-shared");

    assertTrue(repository.existsByManufacturerAndSerial("APPLE", "fp-shared"));
    assertFalse(repository.existsByManufacturerAndSerial("SAMSUNG", "fp-shared"));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            repository.insertProduct(
                4L, request(null), "APPLE", "fp-shared", "another-ciphertext"));
    repository.insertProduct(4L, request(null), "SAMSUNG", "fp-shared", "ciphertext");
  }

  @Test
  void updateChangesAllowedFieldsAndIncrementsFactsVersion() {
    long productId = insert(5L, "APPLE", "fp-update");
    UpdateProductRequest update =
        new UpdateProductRequest(
            "업무용 맥북", LocalDate.of(2025, 1, 2), LocalDate.of(2027, 1, 2), "구매 증빙 확인");

    repository.updateProduct(productId, update);
    ProductResponse product = repository.findOwned(productId, 5L).orElseThrow();
    repository.insertProductAudit(
        productId, 5L, "{\"title\":\"before\"}", "{\"title\":\"after\"}", update.reason());

    assertEquals("업무용 맥북", product.title());
    assertEquals("MacBook Pro 14", product.modelName());
    assertEquals("구매 후 1년", product.warrantyTerms());
    assertEquals(2L, product.factsVersion());
    assertEquals(
        "구매 증빙 확인",
        jdbc.queryForObject(
            "SELECT reason FROM audit_logs WHERE product_id = ?", String.class, productId));
  }

  @Test
  void ownershipTransferChangesOwnerAndIncrementsFactsVersionOnce() {
    long productId = insert(15L, "APPLE", "fp-owner-transfer");

    repository.updateOwner(productId, 16L);

    ProductResponse product = repository.findOwned(productId, 16L).orElseThrow();
    assertEquals(16L, repository.findOwnerId(productId).orElseThrow());
    assertEquals(2L, product.factsVersion());
    assertTrue(repository.findOwned(productId, 15L).isEmpty());
  }

  @Test
  void imagesStoreMetadataAndCanBeReorderedAndMarkedPrimary() {
    long productId = insert(6L, "APPLE", "fp-images");
    long first = repository.insertImage(productId, "product-images/a.png", "image/png", 100L);
    long second = repository.insertImage(productId, "product-images/b.jpg", "image/jpeg", 200L);

    repository.replaceImageOrder(productId, List.of(second, first), second);
    var images = repository.findImages(productId);

    assertEquals(
        List.of(second, first),
        images.stream().map(ProductRepository.ProductImageRecord::imageId).toList());
    assertTrue(images.get(0).primary());
    assertEquals(200L, images.get(0).byteSize());
  }

  @Test
  void deletingAnImageAlsoRepairsOrderAndPrimaryImage() {
    long productId = insert(7L, "APPLE", "fp-delete-image");
    long first = repository.insertImage(productId, "product-images/c.png", "image/png", 10L);
    long second = repository.insertImage(productId, "product-images/d.png", "image/png", 20L);

    repository.softDeleteImage(productId, first);
    var images = repository.findImages(productId);

    assertEquals(1, images.size());
    assertEquals(second, images.get(0).imageId());
    assertTrue(images.get(0).primary());
    assertEquals(0, images.get(0).sortOrder());
  }

  @Test
  void imageContentTypeIsLimitedToTheAllowList() {
    long productId = insert(8L, "APPLE", "fp-content-type");

    assertThrows(
        DataIntegrityViolationException.class,
        () -> repository.insertImage(productId, "product-images/x.gif", "image/gif", 10L));
  }

  private long insert(long ownerId, String manufacturerNormalized, String fingerprint) {
    return insert(ownerId, manufacturerNormalized, fingerprint, null);
  }

  private long insert(
      long ownerId, String manufacturerNormalized, String fingerprint, LocalDate purchaseDate) {
    CreateProductRequest request =
        request(
            new CreateProductRequest.Specifications(
                "Apple M3 Pro", null, 18, 512, "SSD", "14인치", 92, "macOS"),
            purchaseDate);
    long productId =
        repository.insertProduct(
            ownerId, request, manufacturerNormalized, fingerprint, "ciphertext");
    repository.insertLaptopDetails(productId, request.specifications());
    repository.insertFieldAuthors(productId, ownerId, request);
    return productId;
  }

  private static CreateProductRequest request(CreateProductRequest.Specifications specs) {
    return request(specs, null);
  }

  private static CreateProductRequest request(
      CreateProductRequest.Specifications specs, LocalDate purchaseDate) {
    return new CreateProductRequest(
        "MacBook Pro 14",
        "Apple",
        "MacBook Pro 14",
        purchaseDate,
        LocalDate.of(2099, 1, 1),
        "구매 후 1년",
        "SERIAL1234",
        specs);
  }
}
