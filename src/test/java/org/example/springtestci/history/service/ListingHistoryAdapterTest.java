package org.example.springtestci.history.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.entity.HistoryEventType;
import org.example.springtestci.history.repository.HistoryRepository;
import org.example.springtestci.listing.service.ListingHistoryReader.State;
import org.example.springtestci.product.dto.CreateProductRequest;
import org.example.springtestci.product.repository.ProductRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class ListingHistoryAdapterTest {

  private static final String URL =
      "jdbc:h2:mem:listing_history_adapter;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

  private static JdbcTemplate jdbc;
  private static HistoryRepository histories;
  private static ProductRepository products;
  private static ListingHistoryAdapter adapter;

  @BeforeAll
  static void migrate() {
    var dataSource = new SingleConnectionDataSource(URL, "sa", "", true);
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    histories = new HistoryRepository(jdbc);
    products = new ProductRepository(jdbc);
    adapter = new ListingHistoryAdapter(jdbc);
  }

  @Test
  void readsAccumulatedHistoryWithoutPublishingPrivateText() {
    long ownerId = insertUser("reader");
    long productId = insertProduct(ownerId, "listing-history-reader");
    long eventId =
        histories.insertEvent(
            productId, ownerId, request("연락처 010-1234-5678", 250000L), null, null);
    histories.insertAudit(
        productId, ownerId, eventId, "{}", "{}", "고객 이름을 수정함", "HISTORY_CORRECTED");

    var snapshot = adapter.read(productId);

    assertEquals(State.READY, snapshot.state());
    assertFalse(snapshot.sample());
    assertEquals(1, snapshot.events().size());
    var text = snapshot.events().get(0).text();
    assertTrue(!text.reviewed() || text.title() == null);
  }

  @Test
  void publishesAndPrivatizesAllAccumulatedEventsForTheCurrentOwner() {
    long ownerId = insertUser("publisher");
    long productId = insertProduct(ownerId, "listing-history-publisher");
    histories.insertEvent(productId, ownerId, request("정기 점검", null), null, null);
    long listingId = insertListing(productId, ownerId, "ACTIVE");

    adapter.publish(productId, listingId, ownerId);
    assertEquals(
        "PUBLIC",
        jdbc.queryForObject(
            "SELECT visibility FROM product_events WHERE product_id = ?", String.class, productId));

    jdbc.update("UPDATE listings SET status='CANCELLED', active_slot=NULL WHERE id = ?", listingId);
    adapter.makePrivate(productId, listingId, ownerId);
    assertEquals(
        "PRIVATE",
        jdbc.queryForObject(
            "SELECT visibility FROM product_events WHERE product_id = ?", String.class, productId));
  }

  @Test
  void refusesToHideHistoryWhileAnotherListingIsStillOpen() {
    long ownerId = insertUser("open-listing");
    long productId = insertProduct(ownerId, "listing-history-open");
    histories.insertEvent(productId, ownerId, request("점검", null), null, null);
    long listingId = insertListing(productId, ownerId, "ACTIVE");

    assertThrows(CommonException.class, () -> adapter.makePrivate(productId, listingId, ownerId));
  }

  @Test
  void aNewHistoryEventDuringAnOpenListingStartsPublicButKeepsItsTextUnreviewed() {
    long ownerId = insertUser("reserved-history");
    long productId = insertProduct(ownerId, "listing-history-reserved");
    insertListing(productId, ownerId, "RESERVED");

    long eventId = histories.insertEvent(productId, ownerId, request("예약 중 수리", null), null, null);

    assertEquals(
        "PUBLIC",
        jdbc.queryForObject(
            "SELECT visibility FROM product_events WHERE id = ?", String.class, eventId));
    assertFalse(
        jdbc.queryForObject(
            "SELECT public_text_reviewed FROM product_events WHERE id = ?",
            Boolean.class,
            eventId));
  }

  @Test
  void completedTransferCreatesOnePrivateSystemEventWithoutPersonalData() {
    long previousOwnerId = insertUser("previous-owner");
    long newOwnerId = insertUser("new-owner");
    long productId = insertProduct(previousOwnerId, "listing-history-transfer");
    jdbc.update(
        "UPDATE products SET owner_id = ?, facts_version = facts_version + 1 WHERE id = ?",
        newOwnerId,
        productId);
    var writer = new DefaultInternalTransferHistoryWriter(histories, Clock.systemUTC());
    var command =
        new InternalTransferHistoryWriter.Command(
            productId, 9001L, previousOwnerId, newOwnerId, Instant.parse("2026-10-30T01:02:03Z"));

    writer.recordCompletedTransfer(command);
    writer.recordCompletedTransfer(command);

    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM product_events WHERE transfer_request_id = 9001", Integer.class));
    assertEquals(
        "TRANSFER|PRIVATE|소유권 이전 완료",
        jdbc.queryForObject(
            """
            SELECT event_type || '|' || visibility || '|' || title
              FROM product_events WHERE transfer_request_id = 9001
            """,
            String.class));
    assertEquals(
        0,
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM product_events
             WHERE transfer_request_id = 9001
               AND (author_id IS NOT NULL OR content IS NOT NULL OR service_provider IS NOT NULL
                    OR cost IS NOT NULL OR replaced_part IS NOT NULL)
            """,
            Integer.class));
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT facts_version FROM products WHERE id = ?", Long.class, productId));
  }

  private static CreateHistoryEventRequest request(String title, Long cost) {
    return new CreateHistoryEventRequest(
        HistoryEventType.INSPECTION,
        LocalDate.of(2026, 10, 20),
        title,
        "개인정보 검토 전 내용",
        "서비스센터",
        cost,
        null,
        null,
        List.of());
  }

  private static long insertUser(String key) {
    jdbc.update(
        """
        INSERT INTO users(email, password_hash, nickname, role, created_at, updated_at)
        VALUES (?, 'hash', ?, 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """,
        key + "@example.com",
        key.substring(0, Math.min(key.length(), 12)));
    return jdbc.queryForObject(
        "SELECT id FROM users WHERE email = ?", Long.class, key + "@example.com");
  }

  private static long insertProduct(long ownerId, String fingerprint) {
    var request =
        new CreateProductRequest(
            "노트북",
            "Apple",
            "MacBook Pro",
            null,
            null,
            null,
            "SERIAL",
            new CreateProductRequest.Specifications(
                "M3", null, 16, 512, "SSD", "14 inch", 90, "macOS"));
    long productId = products.insertProduct(ownerId, request, "APPLE", fingerprint, "ciphertext");
    products.insertLaptopDetails(productId, request.specifications());
    products.insertFieldAuthors(productId, ownerId, request);
    return productId;
  }

  private static long insertListing(long productId, long sellerId, String status) {
    jdbc.update(
        """
        INSERT INTO listings
            (product_id, seller_id, title, price, condition_grade, description, status,
             content_version, published_at, source_draft_id, source_draft_version, active_slot)
        VALUES (?, ?, '판매글', 100000, 4, '설명', ?, 0, CURRENT_TIMESTAMP, ?, 1,
                CASE WHEN ? IN ('ACTIVE', 'RESERVED') THEN 1 ELSE NULL END)
        """,
        productId,
        sellerId,
        status,
        productId + 10000,
        status);
    return jdbc.queryForObject(
        "SELECT id FROM listings WHERE product_id = ?", Long.class, productId);
  }
}
