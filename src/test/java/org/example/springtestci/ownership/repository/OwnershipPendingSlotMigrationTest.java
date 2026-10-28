package org.example.springtestci.ownership.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 제품당 대기(PENDING) 이전 요청 하나라는 제약이 마이그레이션으로 실제 DB에 걸리는지 확인한다. */
class OwnershipPendingSlotMigrationTest {

  private static final String URL =
      "jdbc:h2:mem:ownership_pending_slot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

  private static String insert(long productId, String status, String slot, String hash) {
    return "insert into ownership_transfers (product_id, from_user_id, token_hash, status, pending_slot, expires_at) "
        + "values ("
        + productId
        + ", 1, '"
        + hash
        + "', '"
        + status
        + "', "
        + slot
        + ", current_timestamp)";
  }

  @Test
  @DisplayName("같은 제품의 대기 요청은 둘 이상 저장되지 않고 종료된 요청은 여러 개 남을 수 있다")
  void allowsOnlyOnePendingTransferPerProduct() throws Exception {
    Flyway.configure()
        .dataSource(URL, "sa", "")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    try (Connection connection = DriverManager.getConnection(URL, "sa", "");
        Statement statement = connection.createStatement()) {
      statement.execute("set referential_integrity false");
      statement.execute(insert(10, "PENDING", "1", "h1"));
      statement.execute(insert(10, "COMPLETED", "null", "h2"));
      statement.execute(insert(10, "CANCELLED", "null", "h3"));
      statement.execute(insert(11, "PENDING", "1", "h4"));

      assertThatThrownBy(() -> statement.execute(insert(10, "PENDING", "1", "h5")))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.execute(insert(12, "PENDING", "null", "h6")))
          .isInstanceOf(SQLException.class);
      assertThatThrownBy(() -> statement.execute(insert(12, "COMPLETED", "1", "h7")))
          .isInstanceOf(SQLException.class);
    }
  }
}
