package org.example.springtestci.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 외부 AI 없이 실제 Spring·Flyway를 실행해 상속된 연결 설정이 시험 DB를 바꾸지 못하는지 확인한다. */
class AiQualityTestContextTest {
  @Test
  void inheritedFlywayAndPoolSettingsCannotRedirectTheQualityDatabase() throws Exception {
    String sentinel =
        "jdbc:h2:mem:quality_sentinel_"
            + UUID.randomUUID().toString().replace("-", "")
            + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    var names =
        List.of(
            "spring.flyway.url",
            "spring.flyway.user",
            "spring.flyway.password",
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.username",
            "spring.datasource.hikari.password");
    var previous = new LinkedHashMap<String, String>();
    try (var connection = DriverManager.getConnection(sentinel, "sa", "")) {
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE isolation_marker(id INT)");
      }
      try {
        for (String name : names) {
          previous.put(name, System.getProperty(name));
          System.setProperty(
              name,
              name.endsWith("url")
                  ? sentinel
                  : name.endsWith("password") ? "foreign-password" : "foreign-user");
        }
        try (var context =
            AiQualityTestContext.start(
                ProductAiLiveQualityTest.Fixtures.class,
                "fixture-not-a-real-api-key",
                "gemini-3.5-flash-lite")) {
          var dataSource = context.getBean(DataSource.class);
          try (var actual = dataSource.getConnection();
              var migration =
                  context
                      .getBean(Flyway.class)
                      .getConfiguration()
                      .getDataSource()
                      .getConnection()) {
            String target = actual.getMetaData().getURL();
            assertTrue(target.startsWith("jdbc:h2:mem:ai_live_quality_"));
            assertEquals(target, migration.getMetaData().getURL());
            assertEquals("SA", migration.getMetaData().getUserName());
          }
          assertTrue(
              new JdbcTemplate(dataSource)
                      .queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)
                  > 0);
        }
        try (var statement = connection.createStatement();
            var rows =
                statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema='public'")) {
          assertTrue(rows.next());
          assertEquals("isolation_marker", rows.getString(1));
          assertFalse(rows.next(), "Foreign sentinel database must not receive migrations");
        }
      } finally {
        previous.forEach(
            (name, value) -> {
              if (value == null) System.clearProperty(name);
              else System.setProperty(name, value);
            });
      }
    }
  }
}
