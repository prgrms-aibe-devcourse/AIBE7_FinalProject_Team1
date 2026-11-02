package org.example.springtestci.document.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 제품 엔티티가 생기기 전까지 products.owner_id를 직접 조회하는 임시 구현. */
@Component
@RequiredArgsConstructor
public class JdbcProductOwnerReader implements ProductOwnerReader {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public Optional<Long> findOwnerId(Long productId) {
    return jdbcTemplate
        .query("select owner_id from products where id = ?", (rs, i) -> rs.getLong(1), productId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Long> lockOwnerId(Long productId) {
    return jdbcTemplate
        .query(
            "select owner_id from products where id = ? for update",
            (rs, i) -> rs.getLong(1),
            productId)
        .stream()
        .findFirst();
  }
}
