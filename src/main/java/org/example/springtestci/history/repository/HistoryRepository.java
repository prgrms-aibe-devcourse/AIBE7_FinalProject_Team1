package org.example.springtestci.history.repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.springtestci.history.dto.CorrectHistoryEventRequest;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.entity.HistoryEventStatus;
import org.example.springtestci.history.entity.HistoryEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryRepository {

  private static final String EVENT_SELECT =
      """
      SELECT id, product_id, author_id, event_type, occurred_on, title, content,
             service_provider, cost, replaced_part, specs_before, specs_after,
             status, created_at, updated_at
        FROM product_events
      """;

  private final JdbcTemplate jdbc;

  public HistoryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean lockOwnedProduct(long productId, long ownerId) {
    return !jdbc.queryForList(
            "SELECT id FROM products WHERE id = ? AND owner_id = ? AND archived_at IS NULL FOR UPDATE",
            Long.class,
            productId,
            ownerId)
        .isEmpty();
  }

  public boolean existsOwnedProduct(long productId, long ownerId) {
    return !jdbc.queryForList(
            "SELECT id FROM products WHERE id = ? AND owner_id = ? AND archived_at IS NULL",
            Long.class,
            productId,
            ownerId)
        .isEmpty();
  }

  /** 제품 잠금 이후 호출해 유효한 외부 직접 승계 요청과 사용자 이력 등록의 경합을 막는다. */
  public boolean hasPendingDirectTransfer(long productId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM ownership_transfers
                 WHERE product_id = ? AND transfer_type = 'DIRECT'
                   AND status = 'PENDING' AND expires_at > CURRENT_TIMESTAMP)
            """,
            Boolean.class,
            productId));
  }

  public ProductSpecifications findSpecifications(long productId) {
    return jdbc.queryForObject(
        """
        SELECT cpu_spec, gpu_spec, ram_gb, storage_gb, storage_type, display_spec,
               battery_health_percent, os_name
          FROM laptop_details WHERE product_id = ?
        """,
        (row, index) ->
            new ProductSpecifications(
                row.getString("cpu_spec"),
                row.getString("gpu_spec"),
                nullableInteger(row, "ram_gb"),
                nullableInteger(row, "storage_gb"),
                row.getString("storage_type"),
                row.getString("display_spec"),
                nullableInteger(row, "battery_health_percent"),
                row.getString("os_name")),
        productId);
  }

  public void replaceSpecifications(long productId, ProductSpecifications specifications) {
    jdbc.update(
        """
        UPDATE laptop_details
           SET cpu_spec = ?, gpu_spec = ?, ram_gb = ?, storage_gb = ?, storage_type = ?,
               display_spec = ?, battery_health_percent = ?, os_name = ?,
               updated_at = CURRENT_TIMESTAMP
         WHERE product_id = ?
        """,
        specifications.cpu(),
        specifications.gpu(),
        specifications.ramGb(),
        specifications.storageGb(),
        specifications.storageType(),
        specifications.display(),
        specifications.batteryHealthPercent(),
        specifications.os(),
        productId);
    jdbc.update(
        "UPDATE products SET facts_version = facts_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        productId);
  }

  public void incrementFactsVersion(long productId) {
    jdbc.update(
        "UPDATE products SET facts_version = facts_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        productId);
  }

  /** 제품 잠금 안에서 이번 history 쓰기가 반영된 최신 사실 버전을 읽는다. */
  public long findFactsVersion(long productId) {
    return jdbc.queryForObject(
        "SELECT facts_version FROM products WHERE id = ?", Long.class, productId);
  }

  public long insertEvent(
      long productId,
      long authorId,
      CreateHistoryEventRequest request,
      String specsBefore,
      String specsAfter) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO product_events
                      (product_id, author_id, event_type, occurred_on, title, content,
                       service_provider, cost, replaced_part, specs_before, specs_after, visibility)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB),
                          CASE WHEN EXISTS (
                              SELECT 1 FROM listings
                               WHERE product_id = ? AND status IN ('ACTIVE', 'RESERVED')
                          ) THEN 'PUBLIC' ELSE 'PRIVATE' END)
                  """,
                  new String[] {"id"});
          statement.setLong(1, productId);
          statement.setLong(2, authorId);
          statement.setString(3, request.eventType().name());
          statement.setDate(4, Date.valueOf(request.occurredOn()));
          statement.setString(5, request.title().trim());
          statement.setString(6, trimToNull(request.content()));
          statement.setString(7, trimToNull(request.serviceProvider()));
          if (request.cost() == null) statement.setObject(8, null);
          else statement.setLong(8, request.cost());
          statement.setString(9, trimToNull(request.replacedPart()));
          statement.setString(10, specsBefore);
          statement.setString(11, specsAfter);
          statement.setLong(12, productId);
          return statement;
        },
        keys);
    return keys.getKey().longValue();
  }

  /** 소유권 모듈이 제품 행을 잠근 뒤 호출하며, 이전 요청당 시스템 이력 한 건만 저장한다. */
  public long insertTransferEvent(
      long productId, long transferRequestId, LocalDate occurredOn, OffsetDateTime completedAt) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO product_events
                      (product_id, author_id, event_type, occurred_on, title, content,
                       service_provider, cost, replaced_part, specs_before, specs_after,
                       status, transfer_request_id, visibility, public_text_reviewed,
                       created_at, updated_at)
                  VALUES (?, NULL, 'TRANSFER', ?, '소유권 이전 완료', NULL,
                          NULL, NULL, NULL, NULL, NULL, 'VALID', ?, 'PRIVATE', TRUE, ?, ?)
                  """,
                  new String[] {"id"});
          statement.setLong(1, productId);
          statement.setDate(2, Date.valueOf(occurredOn));
          statement.setLong(3, transferRequestId);
          statement.setObject(4, completedAt);
          statement.setObject(5, completedAt);
          return statement;
        },
        keys);
    return keys.getKey().longValue();
  }

  public Optional<TransferEventIdentity> findTransferEvent(long transferRequestId) {
    return jdbc
        .query(
            "SELECT id, product_id FROM product_events WHERE transfer_request_id = ?",
            (row, index) -> new TransferEventIdentity(row.getLong("id"), row.getLong("product_id")),
            transferRequestId)
        .stream()
        .findFirst();
  }

  public boolean isCurrentOwner(long productId, long ownerId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM products WHERE id = ? AND owner_id = ? AND archived_at IS NULL",
            Integer.class,
            productId,
            ownerId);
    return count != null && count == 1;
  }

  /** 소유권 이전 완료와 같은 트랜잭션에서 해당 제품의 누적 이력을 모두 비공개로 전환한다. */
  public void makeAllPrivate(long productId) {
    jdbc.update("UPDATE product_events SET visibility = 'PRIVATE' WHERE product_id = ?", productId);
  }

  public List<HistoryEventRecord> findTimeline(long productId) {
    return jdbc.query(
        EVENT_SELECT + " WHERE product_id = ? ORDER BY occurred_on, created_at, id",
        this::mapEvent,
        productId);
  }

  public Optional<HistoryEventRecord> findEvent(long productId, long eventId) {
    return jdbc
        .query(
            EVENT_SELECT + " WHERE product_id = ? AND id = ?", this::mapEvent, productId, eventId)
        .stream()
        .findFirst();
  }

  public List<HistoryEventRecord> findValidSpecificationEventsFrom(long productId, long eventId) {
    return jdbc.query(
        EVENT_SELECT
            + " WHERE product_id = ? AND id >= ? AND status = 'VALID'"
            + " AND specs_after IS NOT NULL ORDER BY id",
        this::mapEvent,
        productId,
        eventId);
  }

  public void correctEvent(
      long eventId, CorrectHistoryEventRequest request, String specsBefore, String specsAfter) {
    jdbc.update(
        """
        UPDATE product_events
           SET occurred_on = ?, title = ?, content = ?, service_provider = ?, cost = ?,
               replaced_part = ?, specs_before = CAST(? AS JSONB),
               specs_after = CAST(? AS JSONB), public_text_reviewed = FALSE,
               updated_at = CURRENT_TIMESTAMP
         WHERE id = ?
        """,
        request.occurredOn(),
        request.title().trim(),
        trimToNull(request.content()),
        trimToNull(request.serviceProvider()),
        request.cost(),
        trimToNull(request.replacedPart()),
        specsBefore,
        specsAfter,
        eventId);
  }

  public void voidEvent(long eventId) {
    jdbc.update(
        "UPDATE product_events SET status = 'VOID', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
        eventId);
  }

  public boolean documentsCanBeLinked(long productId, List<Long> documentIds) {
    if (documentIds.isEmpty()) return true;
    String placeholders = String.join(",", java.util.Collections.nCopies(documentIds.size(), "?"));
    Object[] arguments = new Object[documentIds.size() + 1];
    arguments[0] = productId;
    for (int index = 0; index < documentIds.size(); index++)
      arguments[index + 1] = documentIds.get(index);
    List<Long> linked =
        jdbc.queryForList(
            "SELECT id FROM documents WHERE product_id = ? AND mask_confirmed_at IS NOT NULL"
                + " AND redaction_required_at IS NULL AND id IN ("
                + placeholders
                + ") FOR UPDATE",
            Long.class,
            arguments);
    return linked.size() == documentIds.size();
  }

  public void linkDocuments(long eventId, List<Long> documentIds) {
    for (Long documentId : documentIds) {
      jdbc.update(
          "INSERT INTO event_documents (event_id, document_id) VALUES (?, ?)", eventId, documentId);
      jdbc.update(
          "UPDATE documents SET protected_at = COALESCE(protected_at, CURRENT_TIMESTAMP) WHERE id = ?",
          documentId);
    }
  }

  public Map<Long, List<Long>> findDocumentIds(long productId) {
    Map<Long, List<Long>> result = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT ed.event_id, ed.document_id
          FROM event_documents ed
          JOIN product_events pe ON pe.id = ed.event_id
         WHERE pe.product_id = ? ORDER BY ed.event_id, ed.document_id
        """,
        (org.springframework.jdbc.core.RowCallbackHandler)
            row ->
                result
                    .computeIfAbsent(
                        row.getLong("event_id"), ignored -> new java.util.ArrayList<>())
                    .add(row.getLong("document_id")),
        productId);
    return result;
  }

  /** 감사 로그의 삽입 순서를 기준으로 이벤트별 마지막 정정·무효 기록을 반환한다. */
  public Map<Long, HistoryChangeRecord> findLatestChanges(long productId) {
    Map<Long, HistoryChangeRecord> result = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT event_id, action, reason, created_at
          FROM audit_logs
         WHERE product_id = ? AND event_id IS NOT NULL
         ORDER BY event_id, id
        """,
        (org.springframework.jdbc.core.RowCallbackHandler)
            row ->
                result.put(
                    row.getLong("event_id"),
                    new HistoryChangeRecord(
                        row.getString("action"),
                        row.getString("reason"),
                        row.getObject("created_at", OffsetDateTime.class))),
        productId);
    return result;
  }

  /** 타임라인에서 정정 후 무효 처리까지 빠짐없이 표시할 수 있도록 변경 기록을 발생 순서대로 묶는다. */
  public Map<Long, List<HistoryChangeRecord>> findChanges(long productId) {
    Map<Long, List<HistoryChangeRecord>> result = new LinkedHashMap<>();
    jdbc.query(
        """
        SELECT event_id, action, reason, created_at
          FROM audit_logs
         WHERE product_id = ? AND event_id IS NOT NULL
         ORDER BY event_id, id
        """,
        (org.springframework.jdbc.core.RowCallbackHandler)
            row ->
                result
                    .computeIfAbsent(
                        row.getLong("event_id"), ignored -> new java.util.ArrayList<>())
                    .add(
                        new HistoryChangeRecord(
                            row.getString("action"),
                            row.getString("reason"),
                            row.getObject("created_at", OffsetDateTime.class))),
        productId);
    return result;
  }

  public void insertAudit(
      long productId,
      long actorId,
      long eventId,
      String beforeFacts,
      String afterFacts,
      String reason,
      String action) {
    jdbc.update(
        """
        INSERT INTO audit_logs
            (product_id, actor_id, target_id, event_id, before_facts, after_facts, reason, action)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        productId,
        actorId,
        eventId,
        eventId,
        beforeFacts,
        afterFacts,
        reason.trim(),
        action);
  }

  private HistoryEventRecord mapEvent(java.sql.ResultSet row, int index)
      throws java.sql.SQLException {
    return new HistoryEventRecord(
        row.getLong("id"),
        row.getLong("product_id"),
        (Long) row.getObject("author_id"),
        HistoryEventType.valueOf(row.getString("event_type")),
        row.getObject("occurred_on", LocalDate.class),
        row.getString("title"),
        row.getString("content"),
        row.getString("service_provider"),
        (Long) row.getObject("cost"),
        row.getString("replaced_part"),
        row.getString("specs_before"),
        row.getString("specs_after"),
        HistoryEventStatus.valueOf(row.getString("status")),
        row.getObject("created_at", OffsetDateTime.class),
        row.getObject("updated_at", OffsetDateTime.class));
  }

  private static String trimToNull(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  private static Integer nullableInteger(java.sql.ResultSet row, String column)
      throws java.sql.SQLException {
    Number value = (Number) row.getObject(column);
    return value == null ? null : value.intValue();
  }

  public record ProductSpecifications(
      String cpu,
      String gpu,
      Integer ramGb,
      Integer storageGb,
      String storageType,
      String display,
      Integer batteryHealthPercent,
      String os) {}

  public record HistoryEventRecord(
      long eventId,
      long productId,
      Long authorId,
      HistoryEventType eventType,
      LocalDate occurredOn,
      String title,
      String content,
      String serviceProvider,
      Long cost,
      String replacedPart,
      String specsBefore,
      String specsAfter,
      HistoryEventStatus status,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record HistoryChangeRecord(String action, String reason, OffsetDateTime changedAt) {}

  public record TransferEventIdentity(long eventId, long productId) {}
}
