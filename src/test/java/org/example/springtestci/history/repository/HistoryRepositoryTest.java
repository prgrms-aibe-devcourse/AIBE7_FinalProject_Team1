package org.example.springtestci.history.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.example.springtestci.history.dto.CorrectHistoryEventRequest;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.entity.HistoryEventType;
import org.example.springtestci.product.dto.CreateProductRequest;
import org.example.springtestci.product.repository.ProductRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

class HistoryRepositoryTest {

  private static final String URL =
      "jdbc:h2:mem:history_repository;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

  private static HistoryRepository historyRepository;
  private static ProductRepository productRepository;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrate() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource(URL, "sa", "", true);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    historyRepository = new HistoryRepository(jdbc);
    productRepository = new ProductRepository(jdbc);
  }

  @Test
  void eventAndDocumentLinkAreStoredAndTheDocumentBecomesProtected() {
    long productId = insertProduct(101L, "history-fingerprint-1");
    long documentId = insertDocument(productId, 101L);
    CreateHistoryEventRequest request =
        new CreateHistoryEventRequest(
            HistoryEventType.REPAIR,
            LocalDate.of(2026, 10, 18),
            "키보드 수리",
            null,
            "서비스센터",
            100000L,
            null,
            null,
            List.of(documentId));

    long eventId = historyRepository.insertEvent(productId, 101L, request, null, null);
    assertTrue(historyRepository.documentsCanBeLinked(productId, List.of(documentId)));
    historyRepository.linkDocuments(eventId, List.of(documentId));
    historyRepository.insertAudit(
        productId,
        101L,
        eventId,
        "{\"title\":\"이전\"}",
        "{\"title\":\"수정\"}",
        "제목 오기 수정",
        "HISTORY_CORRECTED");
    historyRepository.insertAudit(
        productId,
        101L,
        eventId,
        "{\"title\":\"수정\"}",
        "{\"title\":\"수정\"}",
        "중복 기록 무효화",
        "HISTORY_VOIDED");

    assertEquals(eventId, historyRepository.findTimeline(productId).get(0).eventId());
    assertEquals(List.of(documentId), historyRepository.findDocumentIds(productId).get(eventId));
    assertEquals("중복 기록 무효화", historyRepository.findLatestChanges(productId).get(eventId).reason());
    assertEquals(
        List.of("제목 오기 수정", "중복 기록 무효화"),
        historyRepository.findChanges(productId).get(eventId).stream()
            .map(HistoryRepository.HistoryChangeRecord::reason)
            .toList());
    assertNotNull(
        jdbc.queryForObject(
            "SELECT protected_at FROM documents WHERE id = ?", Object.class, documentId));
  }

  @Test
  void replacingSpecificationsIncrementsTheProductFactsVersion() {
    long productId = insertProduct(102L, "history-fingerprint-2");
    var before = historyRepository.findSpecifications(productId);

    historyRepository.replaceSpecifications(
        productId,
        new HistoryRepository.ProductSpecifications(
            before.cpu(),
            before.gpu(),
            32,
            before.storageGb(),
            before.storageType(),
            before.display(),
            before.batteryHealthPercent(),
            before.os()));

    assertEquals(32, historyRepository.findSpecifications(productId).ramGb());
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT facts_version FROM products WHERE id = ?", Long.class, productId));
  }

  @Test
  void incrementingFactsVersionMarksAHistoryOnlyCorrectionAsAProductFactChange() {
    long productId = insertProduct(105L, "history-fingerprint-5");

    historyRepository.incrementFactsVersion(productId);

    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT facts_version FROM products WHERE id = ?", Long.class, productId));
  }

  @Test
  void onlyAnUnexpiredPendingDirectTransferBlocksNewHistory() {
    long productId = insertProduct(109L, "history-fingerprint-transfer-block");
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(109,'history-transfer@fixture.invalid','unused','hist109','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    jdbc.update(
        """
        INSERT INTO ownership_transfers
            (product_id, from_user_id, token_hash, status, transfer_type, expires_at, pending_slot)
        VALUES (?, ?, ?, 'PENDING', 'DIRECT', ?, 1)
        """,
        productId,
        109L,
        "history-pending-direct-token",
        OffsetDateTime.now().plusDays(1));

    assertTrue(historyRepository.hasPendingDirectTransfer(productId));

    jdbc.update(
        "UPDATE ownership_transfers SET expires_at = ? WHERE product_id = ?",
        OffsetDateTime.now().minusDays(1),
        productId);
    assertFalse(historyRepository.hasPendingDirectTransfer(productId));
  }

  @Test
  void correctingPublicTextRequiresAReviewOfTheReplacementText() {
    long productId = insertProduct(107L, "history-fingerprint-7");
    long eventId =
        historyRepository.insertEvent(
            productId, 107L, historyRequest(LocalDate.of(2026, 10, 18), "검토된 점검 이력"), null, null);
    jdbc.update("UPDATE product_events SET public_text_reviewed = TRUE WHERE id = ?", eventId);
    var correction =
        new CorrectHistoryEventRequest(
            LocalDate.of(2026, 10, 19), "교체된 제목", "교체된 내용", "교체된 업체", null, null, null, "내용 오기 정정");

    historyRepository.correctEvent(eventId, correction, null, null);

    assertFalse(
        jdbc.queryForObject(
            "SELECT public_text_reviewed FROM product_events WHERE id = ?",
            Boolean.class,
            eventId));
    assertEquals("교체된 제목", historyRepository.findEvent(productId, eventId).orElseThrow().title());
  }

  @Test
  @SuppressWarnings("unchecked")
  void specificationSnapshotsAreStoredAsJsonObjects() throws Exception {
    long productId = insertProduct(103L, "history-fingerprint-3");
    CreateHistoryEventRequest request =
        new CreateHistoryEventRequest(
            HistoryEventType.SPEC_CHANGE,
            LocalDate.of(2026, 10, 18),
            "메모리 증설",
            null,
            null,
            null,
            null,
            Map.of("ramGb", 32),
            List.of());

    long eventId =
        historyRepository.insertEvent(productId, 103L, request, "{\"ramGb\":16}", "{\"ramGb\":32}");
    String stored = historyRepository.findEvent(productId, eventId).orElseThrow().specsAfter();
    var mapper = JsonMapper.builder().build();
    Object first = mapper.readValue(stored, Object.class);
    Map<String, Object> parsed =
        first instanceof String encoded
            ? mapper.readValue(encoded, Map.class)
            : (Map<String, Object>) first;

    assertEquals(32, ((Number) parsed.get("ramGb")).intValue());
  }

  @Test
  void timelineIsOrderedByOccurrenceDateAndThenRegistrationOrder() {
    long productId = insertProduct(104L, "history-fingerprint-4");
    CreateHistoryEventRequest later = historyRequest(LocalDate.of(2026, 10, 19), "두 번째 날짜");
    CreateHistoryEventRequest firstSameDay =
        historyRequest(LocalDate.of(2026, 10, 18), "같은 날짜 첫 등록");
    CreateHistoryEventRequest secondSameDay =
        historyRequest(LocalDate.of(2026, 10, 18), "같은 날짜 두 번째 등록");

    historyRepository.insertEvent(productId, 104L, later, null, null);
    historyRepository.insertEvent(productId, 104L, firstSameDay, null, null);
    historyRepository.insertEvent(productId, 104L, secondSameDay, null, null);

    assertEquals(
        List.of("같은 날짜 첫 등록", "같은 날짜 두 번째 등록", "두 번째 날짜"),
        historyRepository.findTimeline(productId).stream().map(event -> event.title()).toList());
  }

  @Test
  void ownershipChangeHandsTheAccumulatedTimelineToTheNewOwner() {
    long previousOwnerId = 106L;
    long newOwnerId = 206L;
    long productId = insertProduct(previousOwnerId, "history-fingerprint-6");
    long eventId =
        historyRepository.insertEvent(
            productId,
            previousOwnerId,
            historyRequest(LocalDate.of(2026, 10, 18), "이전 소유자의 점검"),
            null,
            null);

    jdbc.update("UPDATE products SET owner_id = ? WHERE id = ?", newOwnerId, productId);

    assertFalse(historyRepository.existsOwnedProduct(productId, previousOwnerId));
    assertTrue(historyRepository.existsOwnedProduct(productId, newOwnerId));
    assertEquals(eventId, historyRepository.findTimeline(productId).get(0).eventId());
    assertEquals(
        previousOwnerId, historyRepository.findTimeline(productId).get(0).authorId().longValue());
  }

  private static CreateHistoryEventRequest historyRequest(LocalDate occurredOn, String title) {
    return new CreateHistoryEventRequest(
        HistoryEventType.INSPECTION, occurredOn, title, null, null, null, null, null, List.of());
  }

  private static long insertProduct(long ownerId, String fingerprint) {
    CreateProductRequest request =
        new CreateProductRequest(
            "MacBook Pro",
            "Apple",
            "MacBook Pro",
            null,
            null,
            null,
            "SERIAL1234",
            new CreateProductRequest.Specifications(
                "M3", null, 16, 512, "SSD", "14 inch", 90, "macOS"));
    long productId =
        productRepository.insertProduct(ownerId, request, "APPLE", fingerprint, "ciphertext");
    productRepository.insertLaptopDetails(productId, request.specifications());
    productRepository.insertFieldAuthors(productId, ownerId, request);
    return productId;
  }

  private static long insertDocument(long productId, long uploaderId) {
    jdbc.update(
        """
        INSERT INTO documents
            (product_id, uploader_id, object_key, content_type, byte_size, mask_confirmed_at)
        VALUES (?, ?, ?, 'image/png', 100, CURRENT_TIMESTAMP)
        """,
        productId,
        uploaderId,
        "documents/" + productId + "/proof.png");
    return jdbc.queryForObject(
        "SELECT id FROM documents WHERE product_id = ?", Long.class, productId);
  }
}
