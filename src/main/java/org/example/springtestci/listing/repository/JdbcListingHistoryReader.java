package org.example.springtestci.listing.repository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실제 이력과 정정·무효 사유를 판매용 읽기 계약에 연결한다. 텍스트는 전달 DTO에서 자동 검사한다. */
@Repository
@Profile("!sale-draft-demo")
@ConditionalOnProperty(
    prefix = "app.features.listing-history",
    name = "enabled",
    havingValue = "true")
public class JdbcListingHistoryReader implements ListingHistoryReader {
  private static final int MAX_EVENTS = 10_000;
  private static final int MAX_CHANGES = 10_000;
  private static final int MAX_CHANGES_PER_EVENT = 100;
  private static final Set<String> EVENT_COLUMNS =
      Set.of("id", "product_id", "author_id", "event_type", "occurred_on", "status", "created_at");
  private static final Set<String> CHANGE_COLUMNS =
      Set.of("id", "product_id", "event_id", "action", "created_at");
  private static final Set<String> TEXT_COLUMNS =
      Set.of("title", "content", "service_provider", "replaced_part");
  private final JdbcTemplate jdbc;

  public JdbcListingHistoryReader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 호출 서비스가 현재 소유권을 확인하고 제품 행을 잠근 트랜잭션 안에서만 사용한다. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Snapshot read(long productId) {
    if (productId <= 0) throw unavailable();
    try {
      // 미배포 스키마를 SQL 오류로 확인하면 PostgreSQL의 호출 트랜잭션도 깨지므로 메타데이터를 먼저 본다.
      var columns = jdbc.execute((ConnectionCallback<SourceColumns>) this::sourceColumns);
      if (columns == null || !columns.ready()) return Snapshot.unavailable();
      // 구형 최소 스키마에서는 메타데이터 조회를 유지한다. 검토 플래그 값은 공개 조건으로 사용하지 않는다.
      String textColumns =
          columns.text()
              ? ", title, content, service_provider, replaced_part"
              : ", NULL AS title, NULL AS content, NULL AS service_provider, NULL AS replaced_part";
      var events =
          jdbc.query(
              """
          SELECT id, product_id, author_id, event_type, occurred_on, status, created_at%s
            FROM product_events WHERE product_id = ?
           ORDER BY occurred_on DESC, created_at DESC, id DESC LIMIT ?
          """
                  .formatted(textColumns),
              (row, index) ->
                  new Event(
                      row.getLong("id"),
                      row.getLong("product_id"),
                      row.getObject("author_id", Long.class),
                      EventType.valueOf(required(row.getString("event_type"))),
                      row.getObject("occurred_on", LocalDate.class),
                      instant(row, "created_at"),
                      EventStatus.valueOf(required(row.getString("status"))),
                      new PublicText(
                          row.getString("title"),
                          row.getString("content"),
                          row.getString("service_provider"),
                          row.getString("replaced_part"),
                          columns.text() && !"TRANSFER".equals(row.getString("event_type"))),
                      List.of()),
              productId,
              MAX_EVENTS + 1);
      if (events.size() > MAX_EVENTS) throw unavailable();
      if (events.isEmpty()) return new Snapshot(State.READY, false, List.of());

      // 이력별 마지막 사유 한 건 대신 실제 정정·무효 기록의 ID와 시각을 묶어서 읽는다.
      // 자유입력 사유만 별도로 검사한다. 감사 전후 원문·수행자·문서·비용 컬럼은 읽지 않는다.
      var changes =
          jdbc.query(
              """
          SELECT a.id, a.event_id, a.created_at, e.event_type, %s AS reason
            FROM audit_logs a JOIN product_events e ON e.id = a.event_id AND e.product_id = a.product_id
           WHERE e.product_id = ? AND a.action IN ('HISTORY_CORRECTED', 'HISTORY_VOIDED')
           ORDER BY a.event_id, a.created_at DESC, a.id DESC LIMIT ?
          """
                  .formatted(columns.reason() ? "a.reason" : "NULL"),
              (row, index) ->
                  new Change(
                      row.getLong("event_id"),
                      new Correction(
                          row.getLong("id"),
                          instant(row, "created_at"),
                          row.getString("reason"),
                          columns.reason() && !"TRANSFER".equals(row.getString("event_type")))),
              productId,
              MAX_CHANGES + 1);
      if (changes.size() > MAX_CHANGES) throw unavailable();
      var byEvent = new HashMap<Long, List<Correction>>();
      for (var change : changes) {
        var values = byEvent.computeIfAbsent(change.eventId(), ignored -> new ArrayList<>());
        values.add(change.correction());
        if (values.size() > MAX_CHANGES_PER_EVENT) throw unavailable();
      }
      return new Snapshot(
          State.READY,
          false,
          events.stream()
              .map(
                  event ->
                      new Event(
                          event.eventId(),
                          event.productId(),
                          event.authorId(),
                          event.eventType(),
                          event.occurredOn(),
                          event.createdAt(),
                          event.status(),
                          event.text(),
                          byEvent.getOrDefault(event.eventId(), List.of())))
              .toList());
    } catch (DataAccessException | IllegalArgumentException failure) {
      throw unavailable();
    }
  }

  private SourceColumns sourceColumns(Connection connection) throws SQLException {
    return new SourceColumns(
        hasColumns(connection, "product_events", EVENT_COLUMNS)
            && hasColumns(connection, "audit_logs", CHANGE_COLUMNS),
        hasColumns(connection, "product_events", TEXT_COLUMNS),
        hasColumns(connection, "audit_logs", Set.of("reason")));
  }

  private boolean hasColumns(Connection connection, String table, Set<String> required)
      throws SQLException {
    var metadata = connection.getMetaData();
    String name = metadata.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT) : table;
    Set<String> columns = new HashSet<>();
    try (var rows =
        metadata.getColumns(connection.getCatalog(), connection.getSchema(), name, null)) {
      while (rows.next()) {
        if (name.equalsIgnoreCase(rows.getString("TABLE_NAME")))
          columns.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
      }
    }
    return columns.containsAll(required);
  }

  private static Instant instant(ResultSet row, String column) throws SQLException {
    var value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static String required(String value) {
    if (value == null) throw unavailable();
    return value;
  }

  private static CommonException unavailable() {
    return new CommonException(ErrorCode.LISTING_HISTORY_UNAVAILABLE);
  }

  private record Change(long eventId, Correction correction) {}

  private record SourceColumns(boolean ready, boolean text, boolean reason) {}
}
