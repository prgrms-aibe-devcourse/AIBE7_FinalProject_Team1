package org.example.springtestci.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code scripts/db/normalize-ownership-version.sql}을 실제 PostgreSQL 격리 스키마에서 검증한다.
 *
 * <p>구버전 소유권 마이그레이션(2702·2707)이 적용된 DB를 시험 전용 보관본으로 재현하고, 정규화한 뒤 현재 마이그레이션이 성공하는지 확인한다. 공유 DB는 건드리지
 * 않는다. {@code DEPLOY_DDL_TEST_ENABLED=true}와 {@code DEPLOY_DDL_TEST_DB_URL/USER/PASSWORD}가 있을 때만
 * 실행한다.
 */
@EnabledIfEnvironmentVariable(named = "DEPLOY_DDL_TEST_ENABLED", matches = "true")
class NormalizeOwnershipVersionPostgresTest {

  private static final String PREFIX = "own_norm_" + UUID.randomUUID().toString().replace("-", "");
  private static final String HISTORY_ROWS =
      "select installed_rank, version, description, type, script, checksum, success, execution_time"
          + " from flyway_schema_history where version in ('2026102702','2026102707','2026102803','2026102804')"
          + " order by installed_rank";
  private static String url;
  private static String user;
  private static String password;
  private static JdbcTemplate admin;
  private static String script;
  private static int schemaCount;

  @BeforeAll
  static void setUp() throws Exception {
    url = System.getenv("DEPLOY_DDL_TEST_DB_URL");
    user = System.getenv("DEPLOY_DDL_TEST_DB_USER");
    password = System.getenv("DEPLOY_DDL_TEST_DB_PASSWORD");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    script =
        Files.readString(
            Path.of("scripts/db/normalize-ownership-version.sql"), StandardCharsets.UTF_8);
  }

  @AfterAll
  static void dropSchemas() {
    for (int i = 1; i <= schemaCount; i++) {
      admin.execute("drop schema if exists " + PREFIX + "_" + i + " cascade");
    }
  }

  private static String newSchemaName() {
    String schema = PREFIX + "_" + (++schemaCount);
    admin.execute("create schema " + schema);
    return schema;
  }

  private static DataSource dataSource(String schema) {
    return new DriverManagerDataSource(
        url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
  }

  /** 구버전 소유권(2702·2707)이 적용된 DB를 시험 전용 보관본으로 재현한다. 2802까지만 적용한다. */
  private static DataSource legacyDatabase() {
    DataSource ds = dataSource(newSchemaName());
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration", "classpath:db/fixtures/h40-legacy-general")
        .target("2026102802")
        .load()
        .migrate();
    return ds;
  }

  /** 스크립트를 psql 래퍼와 같은 방식(한 트랜잭션, 기대 DB·스키마 지정)으로 실행한다. */
  private static void normalize(DataSource ds, String expectedSchema) throws SQLException {
    try (Connection connection = ds.getConnection()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        String database;
        try (var rs = statement.executeQuery("select current_database()")) {
          rs.next();
          database = rs.getString(1);
        }
        statement.execute(
            "select set_config('pastport.expected_database', '" + database + "', true)");
        statement.execute(
            "select set_config('pastport.expected_schema', '" + expectedSchema + "', true)");
        statement.execute(script);
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
  }

  private static String schemaOf(DataSource ds) {
    return new JdbcTemplate(ds).queryForObject("select current_schema()", String.class);
  }

  private static List<Map<String, Object>> history(DataSource ds) {
    return new JdbcTemplate(ds).queryForList(HISTORY_ROWS);
  }

  @Test
  void 구버전_DB는_번호만_바뀌고_이후_현재_마이그레이션이_성공한다() throws Exception {
    DataSource ds = legacyDatabase();
    List<Map<String, Object>> before = history(ds);
    assertEquals(
        List.of("2026102702", "2026102707"), before.stream().map(r -> r.get("version")).toList());

    normalize(ds, schemaOf(ds));

    List<Map<String, Object>> after = history(ds);
    assertEquals(
        List.of("2026102803", "2026102804"), after.stream().map(r -> r.get("version")).toList());
    assertEquals(
        List.of(
            "V2026102803__create_ownership_tables.sql",
            "V2026102804__add_ownership_pending_slot.sql"),
        after.stream().map(r -> r.get("script")).toList());
    // version, script 외의 기록(순서·체크섬·설명·실행 시간)은 그대로여야 한다.
    for (int i = 0; i < before.size(); i++) {
      for (String column :
          List.of(
              "installed_rank", "description", "type", "checksum", "success", "execution_time")) {
        assertEquals(before.get(i).get(column), after.get(i).get(column), column);
      }
    }

    // 정규화한 DB에 현재 마이그레이션(정식 + 배포 복구)을 올려도 이미 있는 소유권 테이블을 다시 만들지 않고 끝난다.
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration", "classpath:db/deploy")
        .ignoreMigrationPatterns("*:*")
        .load()
        .migrate();
    // 이력이 현재 파일과 어긋나지 않는지 무시 패턴 없이도 확인한다.
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration", "classpath:db/deploy")
        .load()
        .validate();
    assertEquals(
        2,
        new JdbcTemplate(ds)
            .queryForObject(
                "select count(*) from flyway_schema_history where version in ('2026102803','2026102804') and success",
                Integer.class));
  }

  @Test
  void 정규화_전에는_현재_마이그레이션이_이미_있음_오류로_실패한다() {
    DataSource ds = legacyDatabase();
    var exception =
        assertThrows(
            RuntimeException.class,
            () ->
                Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration", "classpath:db/deploy")
                    .ignoreMigrationPatterns("*:*")
                    .load()
                    .migrate());
    assertTrue(
        String.valueOf(exception.getMessage()).contains("already exists"), exception.getMessage());
  }

  @Test
  void 두_번_실행해도_결과가_같다() throws Exception {
    DataSource ds = legacyDatabase();
    normalize(ds, schemaOf(ds));
    List<Map<String, Object>> once = history(ds);
    normalize(ds, schemaOf(ds));
    assertEquals(once, history(ds));
  }

  @Test
  void 이미_현재_번호인_DB는_바꾸지_않는다() throws Exception {
    DataSource ds = dataSource(newSchemaName());
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    List<Map<String, Object>> before = history(ds);

    normalize(ds, schemaOf(ds));

    assertEquals(before, history(ds));
  }

  @Test
  void 소유권_이력이_없는_DB는_바꿀_것이_없다() throws Exception {
    DataSource ds = dataSource(newSchemaName());
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .target("2026102802")
        .load()
        .migrate();

    normalize(ds, schemaOf(ds));

    assertEquals(List.of(), history(ds));
  }

  @Test
  void 기대_스키마가_다르면_거부하고_이력을_바꾸지_않는다() {
    DataSource ds = legacyDatabase();
    List<Map<String, Object>> before = history(ds);

    assertThrows(SQLException.class, () -> normalize(ds, "public"));

    assertEquals(before, history(ds));
  }

  @Test
  void 체크섬이_검증된_SQL과_다르면_거부하고_이력을_바꾸지_않는다() {
    DataSource ds = legacyDatabase();
    new JdbcTemplate(ds)
        .update("update flyway_schema_history set checksum = 1 where version = '2026102702'");
    List<Map<String, Object>> before = history(ds);

    assertThrows(SQLException.class, () -> normalize(ds, schemaOf(ds)));

    assertEquals(before, history(ds));
  }

  @Test
  void 대기_슬롯_이력만_있고_테이블_이력이_없으면_거부한다() {
    DataSource ds = legacyDatabase();
    new JdbcTemplate(ds).update("delete from flyway_schema_history where version = '2026102702'");
    List<Map<String, Object>> before = history(ds);

    assertThrows(SQLException.class, () -> normalize(ds, schemaOf(ds)));

    assertEquals(before, history(ds));
  }

  @Test
  void 이력은_있으나_실제_구조가_다르면_거부한다() {
    DataSource ds = legacyDatabase();
    new JdbcTemplate(ds)
        .execute("alter table ownership_transfers drop column pending_slot cascade");
    List<Map<String, Object>> before = history(ds);

    assertThrows(SQLException.class, () -> normalize(ds, schemaOf(ds)));

    assertEquals(before, history(ds));
  }
}
