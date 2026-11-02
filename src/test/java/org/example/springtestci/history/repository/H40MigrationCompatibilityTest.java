package org.example.springtestci.history.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import db.migration.V2026102102__isolate_published_listing_ids;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 합성 신규/demo/옛 일반 DB를 검증하며 팀 DB 적용 사실이나 ownership 업무 매핑을 판정하지 않는다. */
class H40MigrationCompatibilityTest {
  private static final String CURRENT_OWNERSHIP = "V2026102803__create_ownership_tables.sql";
  private static final String OLD_OWNERSHIP = "V2026102702__create_ownership_tables.sql";
  private static final String CURRENT_PENDING = "V2026102804__add_ownership_pending_slot.sql";
  private static final String OLD_PENDING = "V2026102707__add_ownership_pending_slot.sql";
  @TempDir Path legacyResources;
  private SingleConnectionDataSource source;
  private JdbcTemplate jdbc;
  private String schema;
  private boolean postgres;

  @BeforeEach
  void connectDedicatedDatabase() {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    postgres = url != null && !url.isBlank();
    if (postgres && !url.contains("sale_ai_test")) {
      throw new IllegalStateException("Use a dedicated sale_ai_test database only");
    }
    schema = postgres ? "h40_migration_" + UUID.randomUUID().toString().replace("-", "") : "public";
    source =
        new SingleConnectionDataSource(
            postgres
                ? url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema
                : "jdbc:h2:mem:h40_"
                    + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            postgres ? System.getenv("SALE_DRAFT_TEST_DB_USER") : "sa",
            postgres ? System.getenv("SALE_DRAFT_TEST_DB_PASSWORD") : "",
            true);
    jdbc = new JdbcTemplate(source);
    if (postgres) jdbc.execute("CREATE SCHEMA " + schema);
  }

  @AfterEach
  void closeDedicatedSchema() {
    if (source != null) {
      if (postgres) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      source.destroy();
    }
  }

  @Test
  void freshGeneralAndDemoUseGeneralOwnershipAndCardSummaryVersions() {
    candidate("classpath:db/migration", "classpath:db/demo/sale-draft").migrate();
    assertThat(
            jdbc.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version='2026102803'",
                String.class))
        .isEqualTo(CURRENT_OWNERSHIP);
    assertThat(
            jdbc.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version='2026102802'",
                String.class))
        .isEqualTo("V2026102802__version_listing_card_summary.sql");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='2026102702.1' OR script='V2026102702__version_listing_card_summary.sql'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='ownership_transfers' AND column_name IN ('token_hash','expires_at','pending_slot')",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='listings' AND column_name='summary_format_version'",
                Integer.class))
        .isEqualTo(1);
    System.out.println(
        "L-03 VERIFIED general+demo fresh " + databaseKind() + " history=" + history());
  }

  /** 고정 ownership 원문을 현재 번호로 적용한 DB의 이력·데이터·제약과 재실행 방지를 검사한다. */
  @Test
  void previouslyAppliedCanonicalOwnershipValidatesWithoutReexecutingOwnershipOrLosingData()
      throws Exception {
    for (var resource :
        new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql")) {
      if (!CURRENT_OWNERSHIP.equals(resource.getFilename())) {
        try (var input = resource.getInputStream()) {
          Files.copy(input, legacyResources.resolve(resource.getFilename()));
        }
      }
    }
    try (var input =
        new ClassPathResource("db/fixtures/h40-legacy-general/" + OLD_OWNERSHIP).getInputStream()) {
      Files.copy(input, legacyResources.resolve(CURRENT_OWNERSHIP));
    }
    Flyway.configure()
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("filesystem:" + legacyResources.toAbsolutePath())
        .javaMigrations(new V2026102102__isolate_published_listing_ids())
        .target("2026102804")
        .load()
        .migrate();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'legacy-owner@fixture.invalid','unused','owner','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'legacy-buyer@fixture.invalid','unused','buyer','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    jdbc.update(
        "INSERT INTO products(id,category,manufacturer,manufacturer_normalized,model_name,title,owner_id,serial_number_fingerprint,serial_number_ciphertext) VALUES(1,'LAPTOP','maker','MAKER','model','preserved product',1,'legacy-fingerprint','synthetic')");
    jdbc.update(
        "INSERT INTO ownership_transfers(product_id,from_user_id,to_user_id,token_hash,expires_at,transfer_type,status,pending_slot) VALUES(1,1,2,?,?,'INTERNAL','PENDING',1)",
        "0".repeat(64),
        Timestamp.from(Instant.now().plusSeconds(3600)));
    assertThat(
            jdbc.queryForObject(
                "SELECT script FROM flyway_schema_history WHERE version='2026102803'",
                String.class))
        .isEqualTo(CURRENT_OWNERSHIP);
    assertThat(
            jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version='2026102804'",
                Boolean.class))
        .isTrue();
    var beforeHistory = history();
    var beforeRows = jdbc.queryForList("SELECT * FROM ownership_transfers ORDER BY id");
    var beforeProduct = jdbc.queryForMap("SELECT * FROM products WHERE id=1");
    var beforeColumns =
        jdbc.queryForList(
            "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='ownership_transfers' ORDER BY ordinal_position");
    var beforeConstraints =
        jdbc.queryForList(
            "SELECT constraint_name,constraint_type FROM information_schema.table_constraints WHERE table_schema=current_schema() AND table_name='ownership_transfers' ORDER BY constraint_name");
    Flyway current = candidate("classpath:db/migration");

    Flyway.configure()
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .target("2026102804")
        .load()
        .validate();
    current.migrate();
    current.validate();
    assertThat(current.migrate().migrationsExecuted).isZero();

    assertThat(history().subList(0, beforeHistory.size())).isEqualTo(beforeHistory);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='2026102803' AND script=? AND success=true",
                Integer.class,
                CURRENT_OWNERSHIP))
        .isEqualTo(1);
    var upgradedRows = jdbc.queryForList("SELECT * FROM ownership_transfers ORDER BY id");
    assertThat(upgradedRows)
        .allSatisfy(row -> assertThat(row.remove("recipient_accepted_at")).isNull());
    assertThat(upgradedRows).isEqualTo(beforeRows);
    assertThat(jdbc.queryForMap("SELECT * FROM products WHERE id=1")).isEqualTo(beforeProduct);
    assertThat(
            jdbc.queryForList(
                "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='ownership_transfers' AND column_name <> 'recipient_accepted_at' ORDER BY ordinal_position"))
        .isEqualTo(beforeColumns);
    assertThat(
            jdbc.queryForList(
                "SELECT constraint_name,constraint_type FROM information_schema.table_constraints WHERE table_schema=current_schema() AND table_name='ownership_transfers' AND constraint_name <> 'chk_direct_recipient_consent' ORDER BY constraint_name"))
        .isEqualTo(beforeConstraints);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO ownership_transfers(product_id,from_user_id,to_user_id,token_hash,expires_at,transfer_type,status,pending_slot) VALUES(1,1,2,?,?,'INTERNAL','PENDING',1)",
                    "1".repeat(64),
                    Timestamp.from(Instant.now().plusSeconds(3600))))
        .isInstanceOf(DataIntegrityViolationException.class);
    System.out.println(
        "G-03 CANONICAL_GENERAL_PASS "
            + databaseKind()
            + ": validate/migrate succeed; ownership data/constraints/history preserved; before="
            + beforeHistory);
  }

  /** 미지원 옛 소유권 번호는 이력·실제 데이터를 그대로 두고 검증 단계에서 거부한다. */
  @Test
  void oldOwnershipVersionsRequireReviewAndAreNotAutomaticallyRewritten() throws Exception {
    for (var resource :
        new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql")) {
      if (!List.of(CURRENT_OWNERSHIP, CURRENT_PENDING).contains(resource.getFilename())) {
        try (var input = resource.getInputStream()) {
          Files.copy(input, legacyResources.resolve(resource.getFilename()));
        }
      }
    }
    for (String script : List.of(OLD_OWNERSHIP, OLD_PENDING)) {
      try (var input =
          new ClassPathResource("db/fixtures/h40-legacy-general/" + script).getInputStream()) {
        Files.copy(input, legacyResources.resolve(script));
      }
    }
    Flyway.configure()
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("filesystem:" + legacyResources.toAbsolutePath())
        .javaMigrations(new V2026102102__isolate_published_listing_ids())
        .target("2026102707")
        .load()
        .migrate();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'old-owner@fixture.invalid','unused','owner','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'old-buyer@fixture.invalid','unused','buyer','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    jdbc.update(
        "INSERT INTO products(id,category,manufacturer,manufacturer_normalized,model_name,title,owner_id,serial_number_fingerprint,serial_number_ciphertext) VALUES(1,'LAPTOP','maker','MAKER','model','preserved product',1,'old-fingerprint','synthetic')");
    jdbc.update(
        "INSERT INTO ownership_transfers(product_id,from_user_id,to_user_id,token_hash,expires_at,transfer_type,status,pending_slot) VALUES(1,1,2,?,?,'INTERNAL','PENDING',1)",
        "0".repeat(64),
        Timestamp.from(Instant.now().plusSeconds(3600)));
    var beforeHistory = history();
    var beforeRows = jdbc.queryForList("SELECT * FROM ownership_transfers ORDER BY id");
    var beforeProduct = jdbc.queryForMap("SELECT * FROM products WHERE id=1");
    var beforeColumns =
        jdbc.queryForList(
            "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='ownership_transfers' ORDER BY ordinal_position");
    var beforeConstraints =
        jdbc.queryForList(
            "SELECT constraint_name,constraint_type FROM information_schema.table_constraints WHERE table_schema=current_schema() AND table_name='ownership_transfers' ORDER BY constraint_name");
    var current = candidate("classpath:db/migration");
    assertThatThrownBy(current::validate).isInstanceOf(FlywayValidateException.class);
    assertThatThrownBy(current::migrate).isInstanceOf(FlywayValidateException.class);
    assertThat(history()).isEqualTo(beforeHistory);
    assertThat(jdbc.queryForList("SELECT * FROM ownership_transfers ORDER BY id"))
        .isEqualTo(beforeRows);
    assertThat(jdbc.queryForMap("SELECT * FROM products WHERE id=1")).isEqualTo(beforeProduct);
    assertThat(
            jdbc.queryForList(
                "SELECT column_name,data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='ownership_transfers' ORDER BY ordinal_position"))
        .isEqualTo(beforeColumns);
    assertThat(
            jdbc.queryForList(
                "SELECT constraint_name,constraint_type FROM information_schema.table_constraints WHERE table_schema=current_schema() AND table_name='ownership_transfers' ORDER BY constraint_name"))
        .isEqualTo(beforeConstraints);
  }

  private Flyway candidate(String... locations) {
    return Flyway.configure()
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations(locations)
        .load();
  }

  @Test
  void consentMigrationPreservesLegacyCompletedAndPendingDirectWithoutFabricatedConsent() {
    Flyway.configure()
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(source)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .target("2026110202")
        .load()
        .migrate();
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'consent-owner@fixture.invalid','unused','owner','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'consent-buyer@fixture.invalid','unused','buyer','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    jdbc.update(
        "INSERT INTO products(id,category,manufacturer,manufacturer_normalized,model_name,title,owner_id,serial_number_fingerprint,serial_number_ciphertext) VALUES(1,'LAPTOP','maker','MAKER','model','preserved',1,'consent-fixture','synthetic')");
    jdbc.update(
        "INSERT INTO ownership_transfers(product_id,from_user_id,to_user_id,token_hash,expires_at,transfer_type,status,pending_slot,completed_at) VALUES(1,1,2,?,?,'DIRECT','COMPLETED',NULL,CURRENT_TIMESTAMP),(1,1,NULL,?,?,'DIRECT','PENDING',1,NULL)",
        "a".repeat(64),
        Timestamp.from(Instant.now().plusSeconds(3600)),
        "b".repeat(64),
        Timestamp.from(Instant.now().plusSeconds(3600)));
    var before = jdbc.queryForList("SELECT * FROM ownership_transfers ORDER BY id");
    var current = candidate("classpath:db/migration");
    current.migrate();
    current.validate();
    var after = jdbc.queryForList("SELECT * FROM ownership_transfers ORDER BY id");
    assertThat(after).allSatisfy(row -> assertThat(row.remove("recipient_accepted_at")).isNull());
    assertThat(after).isEqualTo(before);
    assertThat(current.migrate().migrationsExecuted).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE ownership_transfers SET recipient_accepted_at=CURRENT_TIMESTAMP WHERE status='PENDING'"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private List<Map<String, Object>> history() {
    return jdbc.queryForList(
        "SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
  }

  private String databaseKind() {
    return postgres ? "PostgreSQL" : "H2";
  }
}
