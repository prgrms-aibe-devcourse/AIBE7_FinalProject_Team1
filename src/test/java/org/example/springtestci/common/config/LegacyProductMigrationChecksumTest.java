package org.example.springtestci.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 공유 DB에 이미 적용된 초기 마이그레이션 V2~V5의 원본 보존 상태를 검증한다.
 *
 * <p>V2~V4는 팀원 커밋 4865d81에서 원문 그대로 복구해 {@code db/legacy}에 보관한다. 이 위치는 기본 Flyway 경로가 아니므로 신규 DB 생성에는
 * 영향이 없다. 이미 적용된 마이그레이션은 한 글자라도 바뀌면 체크섬이 달라지므로 파일을 수정하지 않고 새 마이그레이션으로 변경해야 한다.
 */
class LegacyProductMigrationChecksumTest {

  private static final String MIGRATION = "classpath:db/migration";
  private static final String LEGACY = "classpath:db/legacy";

  /** 공유 DB의 flyway_schema_history에 기록된 체크섬(버전 → 체크섬). */
  private static final Map<String, Integer> SHARED_DB_CHECKSUMS =
      Map.of(
          "2", -1898772826,
          "3", -550867487,
          "4", 1859459753,
          "5", -2031539899);

  /** DB에 적용하지 않고 Flyway가 파일에서 계산한 체크섬만 비교한다. */
  @Test
  void legacyMigrationChecksumsMatchSharedDatabase() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(
                "jdbc:h2:mem:legacy_migration_checksum;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")
            .locations(MIGRATION, LEGACY)
            .load();

    Map<String, Integer> actual = new HashMap<>();
    for (MigrationInfo info : flyway.info().all()) {
      if (info.getVersion() != null
          && SHARED_DB_CHECKSUMS.containsKey(info.getVersion().getVersion())) {
        actual.put(info.getVersion().getVersion(), info.getChecksum());
      }
    }

    assertEquals(SHARED_DB_CHECKSUMS, actual);
  }

  /** 기본 경로에는 V2~V4가 없어야 한다. 신규 DB 생성 순서가 바뀌면 데모 픽스처의 products 생성과 충돌한다. */
  @Test
  void legacyMigrationsAreNotPartOfDefaultLocation() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(
                "jdbc:h2:mem:legacy_migration_default;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")
            .locations(MIGRATION)
            .load();

    for (MigrationInfo info : flyway.info().all()) {
      String version = info.getVersion() == null ? "" : info.getVersion().getVersion();
      assertTrue(
          !java.util.Set.of("2", "3", "4").contains(version),
          "V" + version + " must stay out of db/migration");
    }
  }

  /**
   * V2~V5 레거시 원본과 현재 canonical migration 체인을 함께 적용한 격리 DB에서, 레거시 원본을 Flyway 위치에 포함할 때 strict
   * validate가 통과하고 제외할 때 applied-but-unresolved 검증 오류가 발생하는지 확인한다.
   *
   * <p>이 시험은 공유 DB의 2701/2706 및 2702/2707 특수 이력 수렴을 재현하지 않는다. 해당 이력 정규화는 별도 DB remediation 범위다.
   *
   * <p>환경 변수 {@code DEPLOY_DDL_TEST_ENABLED=true}와 {@code DEPLOY_DDL_TEST_DB_URL/USER/PASSWORD}가 있을
   * 때만 실행하며, 격리 스키마를 만들고 끝나면 지운다.
   */
  @Nested
  @EnabledIfEnvironmentVariable(named = "DEPLOY_DDL_TEST_ENABLED", matches = "true")
  class OnPostgres {

    private static final String PREFIX =
        "legacy_chain_" + UUID.randomUUID().toString().replace("-", "");
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
      return new DriverManagerDataSource(
          url + separator + "currentSchema=" + schema, user, password);
    }

    /** 무시 패턴 없이(엄격하게) 설정한다. */
    private static Flyway strict(DataSource dataSource, String... locations) {
      return Flyway.configure()
          .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
          .dataSource(dataSource)
          .locations(locations)
          .load();
    }

    @Test
    void fullChainWithLegacyOriginalsValidatesStrictlyOnlyWhenOriginalsAreLoaded() {
      DataSource dataSource = newSchema();

      // V2~V5 레거시 원본과 현재 canonical 날짜형 migration을 함께 적용한 격리 DB
      strict(dataSource, MIGRATION, LEGACY).migrate();

      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      Integer applied =
          jdbc.queryForObject(
              "select count(*) from flyway_schema_history "
                  + "where version in ('2','3','4','5') and success",
              Integer.class);
      assertEquals(4, applied);

      // 원본을 함께 읽으면 엄격 검증이 통과한다.
      strict(dataSource, MIGRATION, LEGACY).validate();

      // V2~V5 원본을 제외하면 applied-but-unresolved 상태가 되어 strict validate가 실패한다.
      assertThrows(FlywayValidateException.class, () -> strict(dataSource, MIGRATION).validate());
    }
  }
}
