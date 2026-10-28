package org.example.springtestci.trade.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 예약 취소, 거절, 수락 등 거래 상태 변경에 대한 감사 기록(SEC-08)을 남긴다. */
@Repository
public class TradeAuditRepository {

  private final JdbcTemplate jdbc;

  public TradeAuditRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void log(
      long productId,
      Long actorId,
      long requestId,
      String action,
      String beforeFacts,
      String afterFacts,
      String reason) {
    jdbc.update(
        "INSERT INTO audit_logs(product_id, actor_id, target_id, action, before_facts, after_facts, reason) VALUES (?, ?, ?, ?, ?, ?, ?)",
        productId,
        actorId,
        requestId,
        action,
        beforeFacts,
        afterFacts,
        reason);
  }
}
