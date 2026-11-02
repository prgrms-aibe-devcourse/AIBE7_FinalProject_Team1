package org.example.springtestci.ownership.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DIRECT 발급·수락에서 제품 선행 잠금 뒤 판매 상태를 재검증한다. verify는 잠금 없는 조회만 사용한다. */
@Repository
public class DirectTransferGuard {
  private final JdbcTemplate jdbc;

  public DirectTransferGuard(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean lockSales(long productId) {
    return !jdbc.queryForList(
            "SELECT id FROM listings WHERE product_id=? AND status IN ('ACTIVE','RESERVED') ORDER BY id FOR UPDATE",
            Long.class,
            productId)
        .isEmpty();
  }

  public boolean hasSale(long productId) {
    return jdbc.queryForObject(
            "SELECT COUNT(*) FROM listings WHERE product_id=? AND status IN ('ACTIVE','RESERVED')",
            Long.class,
            productId)
        > 0;
  }
}
