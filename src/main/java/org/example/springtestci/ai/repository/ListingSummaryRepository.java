package org.example.springtestci.ai.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.example.springtestci.ai.entity.ListingSummaryState;
import org.example.springtestci.ai.entity.ListingSummaryState.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** listings의 요약 컬럼만 JDBC로 변경한다. JPA의 판매 조건 저장이 요약 상태를 덮어쓰지 않게 분리한다. */
@Repository
public class ListingSummaryRepository {
  private final JdbcTemplate jdbc;

  public ListingSummaryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 기존 판매글처럼 요약 컬럼이 비어 있으면 미요청 상태로 읽는다. */
  public ListingSummaryState read(long id) {
    return jdbc.queryForObject(
        "SELECT summary_status,summary_text,summary_source_product_version,summary_source_listing_version,summary_attempt_id,summary_error_code,summary_updated_at,summary_format_version FROM listings WHERE id=?",
        (row, n) ->
            new ListingSummaryState(
                row.getString(1) == null ? Status.NOT_REQUESTED : Status.valueOf(row.getString(1)),
                row.getString(2),
                (Long) row.getObject(3),
                (Long) row.getObject(4),
                row.getObject(5, UUID.class),
                row.getString(6),
                row.getTimestamp(7) == null ? null : row.getTimestamp(7).toInstant(),
                row.getInt(8)),
        id);
  }

  /** 이전 제품 사실이 여전히 유효한 경우에만 이전 요약을 보관한다. 새 시도와 실행 시각은 함께 저장한다. */
  public void queue(
      long id,
      long productVersion,
      long listingVersion,
      UUID attempt,
      Instant now,
      boolean retainText,
      Instant availableAt) {
    jdbc.update(
        "UPDATE listings SET summary_status='QUEUED',summary_text=CASE WHEN ? THEN summary_text ELSE NULL END,summary_source_product_version=?,summary_source_listing_version=?,summary_attempt_id=?,summary_error_code=NULL,summary_updated_at=?,summary_requested_at=?,summary_available_at=? WHERE id=?",
        retainText,
        productVersion,
        listingVersion,
        attempt,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(availableAt),
        id);
  }

  /** 같은 시도 ID의 대기 작업을 처리 중으로 바꾼 한 작업자만 true를 받는다. */
  public boolean claim(Pending pending, Instant now) {
    return jdbc.update(
            "UPDATE listings SET summary_status='PROCESSING',summary_updated_at=? WHERE id=? AND summary_attempt_id=? AND summary_status='QUEUED' AND status IN ('ACTIVE','RESERVED') AND (summary_available_at IS NULL OR summary_available_at<=CURRENT_TIMESTAMP)",
            Timestamp.from(now),
            pending.listingId(),
            pending.attemptId())
        == 1;
  }

  /** 현재 처리 중인 시도만 완료로 저장한다. 이미 종료되었거나 시도가 바뀐 글은 갱신하지 않는다. */
  public void ready(Pending pending, String text, Instant now) {
    jdbc.update(
        "UPDATE listings SET summary_status='READY',summary_text=?,summary_format_version=?,summary_error_code=NULL,summary_updated_at=? WHERE id=? AND summary_attempt_id=? AND summary_status='PROCESSING' AND status IN ('ACTIVE','RESERVED')",
        text,
        ListingSummaryState.CARD_FORMAT_VERSION,
        Timestamp.from(now),
        pending.listingId(),
        pending.attemptId());
  }

  /** 시도 번호가 바뀐 뒤 도착한 이전 작업의 오류는 새 결과를 덮어쓰지 않는다. */
  public void fail(Pending pending, String code, Instant now) {
    jdbc.update(
        "UPDATE listings SET summary_status='FAILED',summary_error_code=?,summary_updated_at=? WHERE id=? AND summary_attempt_id=? AND summary_status IN ('QUEUED','PROCESSING')",
        code,
        Timestamp.from(now),
        pending.listingId(),
        pending.attemptId());
  }

  /** 오래 대기한 작업부터 최대 10건을 읽어 한 번에 가져오는 작업량을 제한한다. */
  public List<Pending> pending() {
    return jdbc.query(
        "SELECT id,summary_attempt_id FROM listings WHERE summary_status='QUEUED' AND status IN ('ACTIVE','RESERVED') AND (summary_available_at IS NULL OR summary_available_at<=CURRENT_TIMESTAMP) ORDER BY summary_updated_at,id LIMIT 10",
        (row, n) -> new Pending(row.getLong(1), row.getObject(2, UUID.class)));
  }

  /** 서버 재시작·강제 종료로 남은 작업을 안전한 실패로 바꿔 영구 생성 중 상태를 막는다. */
  public void expire(Instant processingBefore, Instant queuedBefore, Instant now) {
    jdbc.update(
        "UPDATE listings SET summary_status='FAILED',summary_error_code='AI_002',summary_updated_at=? WHERE (summary_status='PROCESSING' AND summary_updated_at<?) OR (summary_status='QUEUED' AND COALESCE(summary_requested_at,summary_updated_at)<?)",
        Timestamp.from(now),
        Timestamp.from(processingBefore),
        Timestamp.from(queuedBefore));
  }

  /** Redis 실행 제한이 풀릴 때까지 같은 시도를 대기시킨다. 아직 외부 공급자를 호출한 것은 아니다. */
  public void defer(Pending pending, Instant availableAt) {
    jdbc.update(
        "UPDATE listings SET summary_status='QUEUED',summary_available_at=? WHERE id=? AND summary_attempt_id=? AND summary_status='PROCESSING'",
        Timestamp.from(availableAt),
        pending.listingId(),
        pending.attemptId());
  }

  public List<Long> publicListingsForProduct(long productId) {
    return jdbc.queryForList(
        "SELECT id FROM listings WHERE product_id=? AND status IN ('ACTIVE','RESERVED') ORDER BY id",
        Long.class,
        productId);
  }

  /** 커밋 직후 이벤트를 놓쳐도 버전 차이가 남은 기존 요약은 주기적으로 접수를 복구한다. */
  public List<Long> staleListings() {
    return jdbc.queryForList(
        "SELECT l.id FROM listings l JOIN products p ON p.id=l.product_id WHERE l.status IN ('ACTIVE','RESERVED') AND p.owner_id=l.seller_id AND p.archived_at IS NULL AND l.summary_status IS NOT NULL AND (l.summary_source_product_version<>p.facts_version OR l.summary_source_listing_version<>l.content_version OR (l.summary_status='READY' AND l.summary_format_version<>?)) ORDER BY l.id LIMIT 10",
        Long.class,
        ListingSummaryState.CARD_FORMAT_VERSION);
  }

  public record Pending(long listingId, UUID attemptId) {}
}
