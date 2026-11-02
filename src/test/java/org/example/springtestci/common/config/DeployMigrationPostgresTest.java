package org.example.springtestci.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * 배포 프로필이 쓰는 {@code db/deploy} 복구 마이그레이션을 실제 PostgreSQL 격리 스키마에서 검증한다.
 *
 * <p>공유 DB를 건드리지 않는다. 시험마다 새 스키마를 만들고 끝나면 지운다. 환경 변수 {@code DEPLOY_DDL_TEST_ENABLED=true}와 {@code
 * DEPLOY_DDL_TEST_DB_URL/USER/PASSWORD}가 있을 때만 실행한다.
 *
 * <ul>
 *   <li>빈 DB: 정식 경로 뒤에 복구 마이그레이션이 거래·이력 구조를 모두 만든다.
 *   <li>pending 경로로 이미 만든 DB: 복구 마이그레이션이 "이미 있음"으로 실패하지 않는다.
 *   <li>이력은 적용됐으나 컬럼이 없는 DB(공유 DB 상황): 복구 마이그레이션이 컬럼을 채운다.
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "DEPLOY_DDL_TEST_ENABLED", matches = "true")
class DeployMigrationPostgresTest {

  private static final String PREFIX =
      "deploy_ddl_" + UUID.randomUUID().toString().replace("-", "");
  private static String url;
  private static String user;
  private static String password;
  private static JdbcTemplate admin;
  private static int schemaCount;

  @BeforeAll
  static void connect() {
    url = System.getenv("DEPLOY_DDL_TEST_DB_URL");
    user = System.getenv("DEPLOY_DDL_TEST_DB_USER");
    password = System.getenv("DEPLOY_DDL_TEST_DB_PASSWORD");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
  }

  @AfterAll
  static void dropSchemas() {
    for (int i = 1; i <= schemaCount; i++) {
      admin.execute("drop schema if exists " + PREFIX + "_" + i + " cascade");
    }
  }

  private static DataSource newSchema() {
    String schema = PREFIX + "_" + (++schemaCount);
    admin.execute("create schema " + schema);
    String separator = url.contains("?") ? "&" : "?";
    return new DriverManagerDataSource(url + separator + "currentSchema=" + schema, user, password);
  }

  /** 배포용 복구 마이그레이션(2026110301~)이 생기기 전까지의 정식 마이그레이션만 적용한다. 공유 DB가 복구 이전에 이 상태였다. */
  private static void migrateBeforeDeploy(DataSource dataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("2026110201")
        .ignoreMigrationPatterns("*:*")
        .load()
        .migrate();
  }

  /** 실제 배포 설정처럼 이력 불일치로 멈추지 않도록 같은 무시 패턴을 쓴다. */
  private static void migrate(DataSource dataSource, boolean outOfOrder, String... locations) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations(locations)
        .outOfOrder(outOfOrder)
        .ignoreMigrationPatterns("*:*")
        .load()
        .migrate();
  }

  private static boolean exists(JdbcTemplate jdbc, String sql, Object... args) {
    Integer count =
        jdbc.queryForObject("select count(*) from (" + sql + ") t", Integer.class, args);
    return count != null && count > 0;
  }

  private static void assertDeployStructure(JdbcTemplate jdbc) {
    for (String table : new String[] {"purchase_requests", "transaction_change_reviews"}) {
      assertTrue(
          exists(
              jdbc,
              "select 1 from information_schema.tables where table_schema = current_schema() and table_name = ?",
              table),
          table + " 테이블이 있어야 한다");
    }
    for (String[] column :
        new String[][] {
          {"product_events", "visibility"},
          {"product_events", "public_text_reviewed"},
          {"audit_logs", "public_reason_reviewed"}
        }) {
      assertTrue(
          exists(
              jdbc,
              "select 1 from information_schema.columns where table_schema = current_schema() and table_name = ? and column_name = ?",
              column[0],
              column[1]),
          column[0] + "." + column[1] + " 컬럼이 있어야 한다");
    }
    assertTrue(
        exists(
            jdbc,
            "select 1 from pg_constraint where conname = 'ck_purchase_requests_cancellation_metadata'"),
        "취소 메타데이터 제약이 있어야 한다");
    assertTrue(
        exists(jdbc, "select 1 from pg_constraint where conname = 'ck_product_events_visibility'"),
        "공개 상태 제약이 있어야 한다");
  }

  @Test
  void 빈_DB는_정식_경로_뒤에_복구_마이그레이션이_거래와_이력_구조를_모두_만든다() {
    DataSource dataSource = newSchema();
    migrate(dataSource, false, "classpath:db/migration", "classpath:db/deploy");

    assertDeployStructure(new JdbcTemplate(dataSource));
  }

  @Test
  void pending_경로로_이미_만든_DB에서도_복구_마이그레이션이_실패하지_않는다() {
    DataSource dataSource = newSchema();
    migrateBeforeDeploy(dataSource);
    // 팀이 기존에 쓰던 방식: 정식 경로 뒤에 pending P2·P3를 out-of-order로 적용한다.
    migrate(
        dataSource,
        true,
        "classpath:db/migration",
        "classpath:db/pending/trade-p2",
        "classpath:db/pending/trade-p3");

    // 이후 배포 설정(정식 + deploy)으로 올려도 "이미 있음" 오류 없이 끝나야 한다.
    migrate(dataSource, false, "classpath:db/migration", "classpath:db/deploy");

    assertDeployStructure(new JdbcTemplate(dataSource));
  }

  @Test
  void 이력은_적용됐지만_컬럼이_없는_DB는_복구_마이그레이션이_컬럼을_채운다() {
    DataSource dataSource = newSchema();
    migrateBeforeDeploy(dataSource);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    // 공유 DB 상황 재현: 그 버전은 적용된 것으로 기록돼 있으나 실제 구조에는 컬럼이 없다.
    jdbc.execute("drop index ix_product_events_publication");
    jdbc.execute("alter table product_events drop constraint ck_product_events_visibility");
    jdbc.execute("alter table product_events drop column visibility");
    jdbc.execute("alter table product_events drop column public_text_reviewed");
    jdbc.execute("alter table audit_logs drop column public_reason_reviewed");

    migrate(dataSource, false, "classpath:db/migration", "classpath:db/deploy");

    assertDeployStructure(jdbc);
  }

  @Test
  void 복구_마이그레이션은_두_번_실행해도_결과가_같다() {
    DataSource dataSource = newSchema();
    migrate(dataSource, false, "classpath:db/migration", "classpath:db/deploy");
    migrate(dataSource, false, "classpath:db/migration", "classpath:db/deploy");

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertDeployStructure(jdbc);
    assertEquals(
        4,
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version like '202611030%'",
            Integer.class));
  }
}
