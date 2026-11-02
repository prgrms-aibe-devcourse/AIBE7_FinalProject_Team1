package org.example.springtestci.admin.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminAccessRepository {
  private final JdbcTemplate jdbc;

  public AdminAccessRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** JWT에 남은 예전 역할 대신 DB의 현재 역할을 확인한다. */
  public boolean isAdmin(long userId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM users WHERE id = ? AND role = 'ADMIN')",
            Boolean.class,
            userId));
  }
}
