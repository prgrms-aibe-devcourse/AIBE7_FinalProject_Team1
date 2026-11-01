package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.repository.JdbcListingHistoryReader;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.example.springtestci.listing.service.ListingHistoryReader.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 실제 SQL로 전체성·범위·스키마 준비 상태를 확인한다. 자유 입력 컬럼 없이도 조회가 가능해야 한다. */
class JdbcListingHistoryReaderTest {
  private SingleConnectionDataSource source;
  private JdbcTemplate jdbc;
  private ListingHistoryReader reader;
  private String schema;
  private static final OffsetDateTime CREATED = OffsetDateTime.parse("2026-10-01T09:00:00+09:00");

  @BeforeEach
  void start() {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    boolean postgres = url != null && !url.isBlank();
    if (postgres && !url.contains("sale_ai_test"))
      throw new IllegalStateException("Use a dedicated sale_ai_test database only");
    schema = postgres ? "history_reader_" + UUID.randomUUID().toString().replace("-", "") : null;
    source =
        new SingleConnectionDataSource(
            postgres
                ? url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema
                : "jdbc:h2:mem:history_reader_"
                    + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            postgres ? System.getenv("SALE_DRAFT_TEST_DB_USER") : "sa",
            postgres ? System.getenv("SALE_DRAFT_TEST_DB_PASSWORD") : "",
            true);
    jdbc = new JdbcTemplate(source);
    if (postgres) jdbc.execute("CREATE SCHEMA " + schema);
    jdbc.execute(
        "CREATE TABLE product_events(id BIGINT PRIMARY KEY,product_id BIGINT,author_id BIGINT,event_type VARCHAR(30),occurred_on DATE,status VARCHAR(20),created_at TIMESTAMP WITH TIME ZONE)");
    jdbc.execute(
        "CREATE TABLE audit_logs(id BIGINT PRIMARY KEY,product_id BIGINT,event_id BIGINT,action VARCHAR(50),created_at TIMESTAMP WITH TIME ZONE)");
    reader = createReader(jdbc);
  }

  protected ListingHistoryReader createReader(JdbcTemplate jdbc) {
    return new JdbcListingHistoryReader(jdbc);
  }

  @AfterEach
  void stop() {
    if (source != null) {
      if (schema != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      source.destroy();
    }
  }

  @Test
  void missingSourceIsUnavailableInsteadOfClaimingAnEmptyHistory() {
    jdbc.execute("DROP TABLE product_events");
    assertEquals(State.UNAVAILABLE, reader.read(101).state());
    assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
  }

  @Test
  void incompleteSourceIsUnavailableUntilItsColumnsExist() {
    jdbc.execute("ALTER TABLE audit_logs DROP COLUMN event_id");
    assertEquals(State.UNAVAILABLE, reader.read(101).state());
    jdbc.execute("ALTER TABLE audit_logs ADD COLUMN event_id BIGINT");
    assertEquals(State.READY, reader.read(101).state());
    assertFalse(reader.read(101).sample());
  }

  @Test
  void completeEmptySourceIsReadyAndNeverASample() {
    var snapshot = reader.read(101);
    assertEquals(State.READY, snapshot.state());
    assertTrue(snapshot.events().isEmpty());
    assertFalse(snapshot.sample());
  }

  @Test
  void readsPreviousOwnersVoidsAndSystemTransfersWithoutAnyFreeTextColumns() {
    event(1, 101, 2L, "PURCHASE", "VALID");
    event(2, 101, 1L, "REPAIR", "VOID");
    event(3, 101, null, "TRANSFER", "VALID");
    event(4, 201, 2L, "REPAIR", "VALID");
    var values = reader.read(101).events();
    assertEquals(List.of(3L, 2L, 1L), values.stream().map(value -> value.eventId()).toList());
    assertNull(values.get(0).authorId());
    assertEquals(2L, values.get(2).authorId());
    assertEquals("VOID", values.get(1).status().name());
    assertEquals(CREATED.plusSeconds(1).toInstant(), values.get(2).createdAt());
    for (var value : values) {
      assertFalse(value.text().reviewed());
      assertNull(value.text().title());
      assertNull(value.text().content());
      assertNull(value.text().serviceProvider());
      assertNull(value.text().replacedPart());
    }
  }

  @Test
  void includesAllCorrectionIdsAndFiltersOtherActionsOrProducts() {
    event(1, 101, 1L, "INSPECTION", "VALID");
    event(2, 201, 2L, "INSPECTION", "VALID");
    change(11, 101, 1, "HISTORY_CORRECTED");
    change(12, 101, 1, "HISTORY_CORRECTED");
    change(13, 101, 1, "HISTORY_VOIDED");
    change(14, 101, 1, "PRODUCT_UPDATED");
    change(15, 201, 1, "HISTORY_CORRECTED");
    change(16, 201, 2, "HISTORY_CORRECTED");
    var changes = reader.read(101).events().get(0).corrections();
    assertEquals(
        List.of(13L, 12L, 11L), changes.stream().map(value -> value.correctionId()).toList());
    assertEquals(CREATED.plusSeconds(13).toInstant(), changes.get(0).correctedAt());
    assertTrue(changes.stream().allMatch(value -> value.reason() == null && !value.reviewed()));
  }

  @Test
  void overLimitEventsFailInsteadOfReturningAPartialTimeline() {
    var rows = new ArrayList<Object[]>();
    for (int id = 1; id <= 10_001; id++)
      rows.add(new Object[] {id, 101, 1, "INSPECTION", LocalDate.of(2025, 1, 1), "VALID", CREATED});
    jdbc.batchUpdate("INSERT INTO product_events VALUES(?,?,?,?,?,?,?)", rows);
    assertUnavailable(() -> reader.read(101));
  }

  @Test
  void overLimitCorrectionsOnOneEventFailWithoutDroppingOldCorrections() {
    event(1, 101, 1L, "INSPECTION", "VALID");
    var rows = new ArrayList<Object[]>();
    for (int id = 1; id <= 101; id++)
      rows.add(new Object[] {id, 101, 1, "HISTORY_CORRECTED", CREATED});
    jdbc.batchUpdate("INSERT INTO audit_logs VALUES(?,?,?,?,?)", rows);
    assertUnavailable(() -> reader.read(101));
  }

  @Test
  void totalCorrectionLimitDoesNotSilentlyOmitLaterEvents() {
    for (int id = 1; id <= 101; id++) event(id, 101, 1L, "INSPECTION", "VALID");
    var rows = new ArrayList<Object[]>();
    for (int id = 1; id <= 10_001; id++)
      rows.add(new Object[] {id, 101, (id - 1) / 100 + 1, "HISTORY_CORRECTED", CREATED});
    jdbc.batchUpdate("INSERT INTO audit_logs VALUES(?,?,?,?,?)", rows);
    assertUnavailable(() -> reader.read(101));
  }

  @Test
  void unknownSourceEnumsFailInsteadOfExposingAPartialSnapshot() {
    event(1, 101, 1L, "INSPECTION", "VALID");
    event(2, 101, 1L, "UNKNOWN", "VALID");
    assertUnavailable(() -> reader.read(101));
    jdbc.update("UPDATE product_events SET event_type='INSPECTION',status='UNKNOWN' WHERE id=2");
    assertUnavailable(() -> reader.read(101));
  }

  @Test
  void refusesInvalidProductIds() {
    assertUnavailable(() -> reader.read(0));
    assertUnavailable(() -> reader.read(-1));
  }

  @Test
  void databaseReviewBooleansCannotBypassAutomaticPublicFieldChecks() {
    jdbc.execute("ALTER TABLE product_events ADD COLUMN title VARCHAR(200)");
    jdbc.execute("ALTER TABLE product_events ADD COLUMN content VARCHAR(2000)");
    jdbc.execute("ALTER TABLE product_events ADD COLUMN service_provider VARCHAR(200)");
    jdbc.execute("ALTER TABLE product_events ADD COLUMN replaced_part VARCHAR(200)");
    jdbc.execute("ALTER TABLE product_events ADD COLUMN public_text_reviewed BOOLEAN DEFAULT TRUE");
    jdbc.execute("ALTER TABLE audit_logs ADD COLUMN reason VARCHAR(500)");
    jdbc.execute("ALTER TABLE audit_logs ADD COLUMN public_reason_reviewed BOOLEAN DEFAULT TRUE");
    event(1, 101, 1L, "REPAIR", "VALID");
    change(11, 101, 1, "HISTORY_CORRECTED");
    String privateText = "PRIVATE_OWNER_TEXT private@fixture.invalid";
    jdbc.update(
        "UPDATE product_events SET title=?,content=?,service_provider=?,replaced_part=? WHERE id=1",
        privateText,
        privateText,
        privateText,
        privateText);
    jdbc.update("UPDATE audit_logs SET reason=? WHERE id=11", privateText);
    var snapshot = reader.read(101);
    assertEquals(State.READY, snapshot.state());
    assertFalse(snapshot.toString().contains("PRIVATE_OWNER_TEXT"));
    assertTrue(snapshot.events().get(0).text().reviewed());
    assertNull(snapshot.events().get(0).text().content());
    assertTrue(snapshot.events().get(0).corrections().get(0).reviewed());
    assertNull(snapshot.events().get(0).corrections().get(0).reason());
    assertEquals(
        privateText,
        jdbc.queryForObject("SELECT content FROM product_events WHERE id=1", String.class));
    assertEquals(
        privateText,
        jdbc.queryForObject("SELECT reason FROM audit_logs WHERE id=11", String.class));
  }

  @Test
  void exposesCurrentAndPreviousOwnerDetailsAndReasonsWithoutStoredApprovals() {
    jdbc.execute("ALTER TABLE product_events ADD COLUMN title VARCHAR(200)");
    jdbc.execute("ALTER TABLE product_events ADD COLUMN content VARCHAR(2000)");
    jdbc.execute("ALTER TABLE product_events ADD COLUMN service_provider VARCHAR(200)");
    jdbc.execute("ALTER TABLE product_events ADD COLUMN replaced_part VARCHAR(200)");
    jdbc.execute(
        "ALTER TABLE product_events ADD COLUMN public_text_reviewed BOOLEAN DEFAULT FALSE");
    jdbc.execute("ALTER TABLE audit_logs ADD COLUMN reason VARCHAR(500)");
    jdbc.execute("ALTER TABLE audit_logs ADD COLUMN public_reason_reviewed BOOLEAN DEFAULT FALSE");
    event(1, 101, 2L, "PART_REPLACEMENT", "VALID");
    event(2, 101, 1L, "REPAIR", "VOID");
    event(3, 101, null, "TRANSFER", "VALID");
    jdbc.update(
        "UPDATE product_events SET title='배터리 교체', content='교체 후 충전 동작 확인', service_provider='공식 서비스센터', replaced_part='배터리'");
    change(11, 101, 1, "HISTORY_CORRECTED");
    change(12, 101, 2, "HISTORY_VOIDED");
    jdbc.update("UPDATE audit_logs SET reason='발생일 오기입 정정' WHERE id=11");
    jdbc.update("UPDATE audit_logs SET reason='중복 등록으로 무효 처리' WHERE id=12");
    var events = reader.read(101).events();
    assertNull(events.get(0).text().content());
    assertEquals("중복 등록으로 무효 처리", events.get(1).corrections().get(0).reason());
    var previous = events.get(2);
    assertEquals(2L, previous.authorId());
    assertEquals("배터리 교체", previous.text().title());
    assertEquals("교체 후 충전 동작 확인", previous.text().content());
    assertEquals("공식 서비스센터", previous.text().serviceProvider());
    assertEquals("배터리", previous.text().replacedPart());
    assertEquals("발생일 오기입 정정", previous.corrections().get(0).reason());
    assertFalse(
        jdbc.queryForObject(
            "SELECT public_text_reviewed FROM product_events WHERE id=1", Boolean.class));
    assertFalse(
        jdbc.queryForObject(
            "SELECT public_reason_reviewed FROM audit_logs WHERE id=11", Boolean.class));
  }

  private void event(long id, long product, Long author, String type, String status) {
    jdbc.update(
        "INSERT INTO product_events(id,product_id,author_id,event_type,occurred_on,status,created_at) VALUES(?,?,?,?,?,?,?)",
        id,
        product,
        author,
        type,
        LocalDate.of(2025, 1, 1),
        status,
        CREATED.plusSeconds(id));
  }

  private void change(long id, long product, long event, String action) {
    jdbc.update(
        "INSERT INTO audit_logs(id,product_id,event_id,action,created_at) VALUES(?,?,?,?,?)",
        id,
        product,
        event,
        action,
        CREATED.plusSeconds(id));
  }

  private void assertUnavailable(org.junit.jupiter.api.function.Executable request) {
    assertEquals(
        ErrorCode.LISTING_HISTORY_UNAVAILABLE,
        assertThrows(CommonException.class, request).errorCode());
  }
}
