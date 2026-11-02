package org.example.springtestci.ownership.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.example.springtestci.trade.service.InternalTradeCompletionPort;
import org.example.springtestci.trade.service.InternalTransferPendingWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** 정식 ownership/history/document 테이블만 사용한다. 호출 서비스의 거래 트랜잭션에 참여한다. */
@Repository
public class InternalTradeOwnershipRepository {
  private static final String CONNECTION =
      """
      SELECT count(*) FROM purchase_requests pr
        JOIN listings l ON l.id=pr.listing_id
        JOIN chat_rooms cr ON cr.id=pr.room_id AND cr.listing_id=l.id
       WHERE pr.id=? AND l.product_id=? AND l.seller_id=?
         AND cr.seller_id=? AND cr.inquirer_id=? AND pr.request_status='ACCEPTED'
         AND pr.transaction_status=? AND l.status=?
      """;
  private final JdbcTemplate jdbc;

  public InternalTradeOwnershipRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 상위 거래 서비스와 같은 제품 행 잠금이다. 완료 재시도를 위해 보관 여부도 함께 읽는다. */
  public Optional<Product> lockProduct(long productId) {
    return jdbc
        .query(
            "SELECT owner_id,facts_version,archived_at FROM products WHERE id=? FOR UPDATE",
            (row, index) ->
                new Product(
                    row.getLong("owner_id"),
                    row.getLong("facts_version"),
                    row.getObject("archived_at") != null),
            productId)
        .stream()
        .findFirst();
  }

  /** 실제 수락된 요청·예약 판매글·원래 방의 양측 사용자를 모두 확인한다. */
  public boolean matchesReservation(InternalTransferPendingWriter.Command c) {
    return jdbc.queryForObject(
            CONNECTION,
            Long.class,
            c.purchaseRequestId(),
            c.productId(),
            c.fromUserId(),
            c.fromUserId(),
            c.toUserId(),
            "RESERVED",
            "RESERVED")
        == 1;
  }

  /** 거래 서비스가 양측 최신 확인을 저장하고 SOLD/COMPLETED로 전환했는지 재검사한다. */
  public boolean matchesCompletion(InternalTradeCompletionPort.Command c) {
    return jdbc.queryForObject(
            CONNECTION
                + " AND l.id=? AND pr.seller_confirmed_version=? AND pr.buyer_confirmed_version=?",
            Long.class,
            c.purchaseRequestId(),
            c.productId(),
            c.fromUserId(),
            c.fromUserId(),
            c.toUserId(),
            "COMPLETED",
            "SOLD",
            c.listingId(),
            c.confirmedFactsVersion(),
            c.confirmedFactsVersion())
        == 1;
  }

  /** 제품 잠금을 가진 상태에서 이 요청의 이전 행을 잠근다. 중복 행을 숨기지 않고 서비스에서 거부한다. */
  public List<Transfer> transfersForRequest(long requestId) {
    return jdbc.query(
        "SELECT id,product_id,from_user_id,to_user_id,status,transfer_type FROM ownership_transfers WHERE purchase_request_id=? ORDER BY id FOR UPDATE",
        (row, index) ->
            new Transfer(
                row.getLong("id"),
                row.getLong("product_id"),
                row.getLong("from_user_id"),
                row.getObject("to_user_id", Long.class),
                row.getString("status"),
                row.getString("transfer_type")),
        requestId);
  }

  /** 외부 링크만 만료시킨다. 내부 예약은 시간 경과로 자동 취소·만료하지 않는다. */
  public void expireDirectRequests(long productId, Instant now) {
    jdbc.update(
        "UPDATE ownership_transfers SET status='EXPIRED',pending_slot=NULL WHERE product_id=? AND transfer_type='DIRECT' AND status='PENDING' AND expires_at<=?",
        productId,
        Timestamp.from(now));
  }

  /** token_hash는 내부 식별용 난수 해시이며 원문 링크·QR은 발급하지 않는다. 제품별 대기 유일 제약을 사용한다. */
  public long insertPending(InternalTransferPendingWriter.Command c, String hash, Instant now) {
    var keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var s =
              connection.prepareStatement(
                  "INSERT INTO ownership_transfers(product_id,from_user_id,to_user_id,token_hash,status,transfer_type,purchase_request_id,expires_at,pending_slot,created_at) VALUES(?,?,?,?,'PENDING','INTERNAL',?,?,1,?)",
                  new String[] {"id"});
          s.setLong(1, c.productId());
          s.setLong(2, c.fromUserId());
          s.setLong(3, c.toUserId());
          s.setString(4, hash);
          s.setLong(5, c.purchaseRequestId());
          // 기존 NOT NULL 스키마를 유지한다. 내부 예약에는 외부 링크의 24시간 만료를 적용하지 않는다.
          s.setTimestamp(6, Timestamp.from(Instant.parse("9999-12-31T23:59:59Z")));
          s.setTimestamp(7, Timestamp.from(now));
          return s;
        },
        keys);
    return keys.getKey().longValue();
  }

  /** 일치하는 INTERNAL/PENDING 한 건만 확정하고 제품별 대기 슬롯을 해제한다. */
  public int completeTransfer(long transferId, Instant completedAt) {
    return jdbc.update(
        "UPDATE ownership_transfers SET status='COMPLETED',pending_slot=NULL,completed_at=? WHERE id=? AND transfer_type='INTERNAL' AND status='PENDING'",
        Timestamp.from(completedAt),
        transferId);
  }

  /** 확인한 소유자·버전이 여전히 일치할 때만 소유자와 사실 버전을 함께 바꾼다. */
  public int transferOwner(InternalTradeCompletionPort.Command c) {
    return jdbc.update(
        "UPDATE products SET owner_id=?,facts_version=facts_version+1,updated_at=? WHERE id=? AND owner_id=? AND facts_version=? AND archived_at IS NULL",
        c.toUserId(),
        Timestamp.from(c.completedAt()),
        c.productId(),
        c.fromUserId(),
        c.confirmedFactsVersion());
  }

  /** 승계된 마스킹 사본은 삭제 제한만 추가한다. 업로더·파일·재마스킹 차단 상태는 보존한다. */
  public void protectDocuments(long productId, Instant at) {
    jdbc.update(
        "UPDATE documents SET protected_at=COALESCE(protected_at,?) WHERE product_id=?",
        Timestamp.from(at),
        productId);
  }

  public record Product(long ownerId, long factsVersion, boolean archived) {}

  public record Transfer(
      long id, long productId, long fromUserId, Long toUserId, String status, String type) {}
}
