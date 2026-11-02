package org.example.springtestci.admin.repository;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 양측 확인 버전과 미확인 건수만 읽는 운영 투영이다. 변경 사본·문서·채팅 본문은 읽지 않는다. */
@Repository
public class AdminProgressRepository {
  private final NamedParameterJdbcTemplate jdbc;

  public AdminProgressRepository(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** P3/P4가 활성화된 예약 거래만 호출한다. 잠금·갱신 없이 같은 읽기 트랜잭션의 상태를 조합한다. */
  public Optional<Source> find(long requestId) {
    return jdbc
        .query(
            """
        SELECT p.facts_version, pr.seller_confirmed_version, pr.buyer_confirmed_version,
          CASE WHEN p.id IS NOT NULL AND p.archived_at IS NULL AND p.owner_id = l.seller_id
            AND l.status = 'RESERVED' AND cr.listing_id = l.id AND cr.seller_id = l.seller_id
            AND cr.inquirer_id <> cr.seller_id THEN TRUE ELSE FALSE END AS connected,
          (SELECT COUNT(*) FROM transaction_change_reviews r
            WHERE r.purchase_request_id = pr.id AND r.decision IS NULL) AS unreviewed_count
        FROM purchase_requests pr JOIN listings l ON l.id = pr.listing_id
        JOIN chat_rooms cr ON cr.id = pr.room_id LEFT JOIN products p ON p.id = l.product_id
        WHERE pr.id = :id
        """,
            Map.of("id", requestId),
            (r, index) ->
                new Source(
                    r.getObject("facts_version", Long.class),
                    r.getObject("seller_confirmed_version", Long.class),
                    r.getObject("buyer_confirmed_version", Long.class),
                    r.getBoolean("connected"),
                    r.getLong("unreviewed_count")))
        .stream()
        .findFirst();
  }

  public record Source(
      Long factsVersion,
      Long sellerConfirmedVersion,
      Long buyerConfirmedVersion,
      boolean connected,
      long unreviewedCount) {}
}
