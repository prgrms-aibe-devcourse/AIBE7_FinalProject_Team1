package org.example.springtestci.admin.repository;

import java.time.OffsetDateTime;
import java.util.Map;
import org.example.springtestci.admin.dto.AdminDtos.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 거래에 직접 연결된 저장 기록을 읽는다. 문서 원문·채팅·이전 토큰·변경 사본은 조회하지 않는다. */
@Repository
public class AdminTimelineRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public AdminTimelineRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  // target_id는 종류별로 의미가 다르므로 행위와 제품, 요청/이전의 실제 연결을 모두 검사한다.
  private static final String BASE =
      """
      WITH trade AS (
        SELECT pr.*, l.product_id, l.seller_id, l.published_at, cr.inquirer_id AS buyer_id
        FROM purchase_requests pr JOIN listings l ON l.id = pr.listing_id
        JOIN chat_rooms cr ON cr.id = pr.room_id WHERE pr.id = :id
      ), transfers AS (
        SELECT ot.* FROM ownership_transfers ot JOIN trade t
          ON ot.purchase_request_id = t.id AND ot.product_id = t.product_id
        WHERE ot.transfer_type = 'INTERNAL'
      ), events AS (
        SELECT 'LISTING' AS source, t.listing_id AS source_id, 'LISTING_PUBLISHED' AS action,
          t.published_at AS occurred_at, t.seller_id AS actor_id, 'MEMBER' AS actor_type,
          CAST(NULL AS VARCHAR(500)) AS reason, CAST(NULL AS BIGINT) AS event_id,
          (SELECT MIN(a.id) FROM audit_logs a WHERE a.product_id = t.product_id
             AND a.target_id = t.listing_id AND a.action = 'LISTING_PUBLISHED'
             AND a.result = 'SUCCESS') AS audit_id
        FROM trade t WHERE t.published_at IS NOT NULL
        UNION ALL
        SELECT 'AUDIT', a.id, a.action, a.created_at, a.actor_id,
          CASE WHEN a.actor_id IS NULL THEN 'SYSTEM' ELSE 'MEMBER' END,
          a.reason, a.event_id, a.id
        FROM audit_logs a JOIN trade t ON a.product_id = t.product_id AND a.target_id = t.id
        WHERE a.result = 'SUCCESS' AND a.action IN ('PURCHASE_REQUEST_ACCEPTED',
          'PURCHASE_REQUEST_CANCELLED', 'PURCHASE_REQUEST_REJECTED',
          'TRADE_RESERVATION_CANCELLED', 'TRADE_COMPLETED')
        UNION ALL
        SELECT 'AUDIT', a.id, a.action, a.created_at, a.actor_id,
          CASE WHEN a.actor_id IS NULL THEN 'SYSTEM' ELSE 'MEMBER' END,
          a.reason, a.event_id, a.id
        FROM audit_logs a JOIN transfers ot ON a.product_id = ot.product_id AND a.target_id = ot.id
        WHERE a.result = 'SUCCESS' AND a.action IN ('OWNERSHIP_TRANSFER_CREATED',
          'OWNERSHIP_TRANSFERRED', 'OWNERSHIP_TRANSFER_CANCELLED')
        UNION ALL
        SELECT 'TRANSACTION', t.id, 'TRADE_RESERVATION_CANCELLED', t.cancelled_at,
          t.cancelled_by_id,
          CASE WHEN t.cancelled_by_id IS NULL THEN 'UNRECORDED' ELSE 'MEMBER' END,
          t.cancel_reason, CAST(NULL AS BIGINT), CAST(NULL AS BIGINT)
        FROM trade t WHERE t.transaction_status = 'CANCELLED' AND t.cancelled_at IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM audit_logs a WHERE a.product_id = t.product_id
            AND a.target_id = t.id AND a.action = 'TRADE_RESERVATION_CANCELLED' AND a.result = 'SUCCESS')
        UNION ALL
        SELECT 'OWNERSHIP', ot.id, 'OWNERSHIP_TRANSFER_CREATED', ot.created_at,
          CAST(NULL AS BIGINT), 'UNRECORDED', CAST(NULL AS VARCHAR(500)),
          CAST(NULL AS BIGINT), CAST(NULL AS BIGINT)
        FROM transfers ot WHERE ot.created_at IS NOT NULL AND NOT EXISTS (
          SELECT 1 FROM audit_logs a WHERE a.product_id = ot.product_id AND a.target_id = ot.id
            AND a.action = 'OWNERSHIP_TRANSFER_CREATED' AND a.result = 'SUCCESS')
        UNION ALL
        SELECT 'OWNERSHIP', ot.id, 'OWNERSHIP_TRANSFERRED', ot.completed_at,
          CAST(NULL AS BIGINT), 'UNRECORDED', CAST(NULL AS VARCHAR(500)),
          CAST(NULL AS BIGINT), CAST(NULL AS BIGINT)
        FROM transfers ot WHERE ot.completed_at IS NOT NULL AND NOT EXISTS (
          SELECT 1 FROM audit_logs a WHERE a.product_id = ot.product_id AND a.target_id = ot.id
            AND a.action = 'OWNERSHIP_TRANSFERRED' AND a.result = 'SUCCESS')
        UNION ALL
        SELECT 'OWNERSHIP', ot.id, 'OWNERSHIP_TRANSFER_CANCELLED', ot.cancelled_at,
          CAST(NULL AS BIGINT), 'UNRECORDED', CAST(NULL AS VARCHAR(500)),
          CAST(NULL AS BIGINT), CAST(NULL AS BIGINT)
        FROM transfers ot WHERE ot.cancelled_at IS NOT NULL AND NOT EXISTS (
          SELECT 1 FROM audit_logs a WHERE a.product_id = ot.product_id AND a.target_id = ot.id
            AND a.action = 'OWNERSHIP_TRANSFER_CANCELLED' AND a.result = 'SUCCESS')
      """;

  private static final String REVIEWS =
      """
        UNION ALL
        SELECT 'CHANGE_REVIEW', r.event_id, 'RESERVATION_CHANGE_RECORDED', r.created_at,
          CAST(NULL AS BIGINT), 'SYSTEM', CAST(NULL AS VARCHAR(500)), r.event_id, CAST(NULL AS BIGINT)
        FROM transaction_change_reviews r JOIN trade t ON r.purchase_request_id = t.id
        JOIN product_events pe ON pe.id = r.event_id AND pe.product_id = t.product_id
        UNION ALL
        SELECT 'CHANGE_REVIEW', r.event_id,
          CASE WHEN r.decision = 'KEEP' THEN 'RESERVATION_CHANGE_KEPT' ELSE 'RESERVATION_CHANGE_CANCELLED' END,
          r.reviewed_at, t.buyer_id, 'MEMBER', CAST(NULL AS VARCHAR(500)), r.event_id, CAST(NULL AS BIGINT)
        FROM transaction_change_reviews r JOIN trade t ON r.purchase_request_id = t.id
        JOIN product_events pe ON pe.id = r.event_id AND pe.product_id = t.product_id
        WHERE r.reviewed_at IS NOT NULL AND r.decision IN ('KEEP', 'CANCEL')
      """;

  /** P3 비활성 환경에서는 아직 없는 변경 확인 테이블을 참조하지 않는다. 건수와 페이지는 같은 읽기 트랜잭션이다. */
  public Page<TimelineEntry> timeline(long id, int page, int size, boolean reviewsEnabled) {
    String query = BASE + (reviewsEnabled ? REVIEWS : "") + ") ";
    var params = Map.of("id", id, "size", size, "offset", page * size);
    long total = jdbc.queryForObject(query + "SELECT COUNT(*) FROM events", params, Long.class);
    var items =
        jdbc.query(
            query
                + """
                SELECT e.*, u.nickname, u.email FROM events e LEFT JOIN users u ON u.id = e.actor_id
                ORDER BY e.occurred_at ASC, e.source ASC, e.source_id ASC, e.action ASC
                LIMIT :size OFFSET :offset
                """,
            params,
            (r, row) -> {
              Number actorId = (Number) r.getObject("actor_id");
              return new TimelineEntry(
                  r.getString("source")
                      + ":"
                      + r.getLong("source_id")
                      + ":"
                      + r.getString("action"),
                  r.getString("action"),
                  r.getString("source"),
                  r.getObject("occurred_at", OffsetDateTime.class),
                  r.getString("actor_type"),
                  actorId == null
                      ? null
                      : new MemberSummary(
                          actorId.longValue(), r.getString("nickname"), r.getString("email")),
                  r.getString("reason"),
                  r.getObject("event_id", Long.class),
                  r.getObject("audit_id", Long.class));
            });
    return new Page<>(items, page, size, total);
  }
}
