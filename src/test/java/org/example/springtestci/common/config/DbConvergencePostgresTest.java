package org.example.springtestci.common.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.example.db.ConvergenceTool;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Source-backed synthetic lineages in disposable schemas; never a shared DB clone. */
@EnabledIfEnvironmentVariable(named = "DEPLOY_DDL_TEST_ENABLED", matches = "true")
class DbConvergencePostgresTest {
  private static final String URL = System.getenv("DEPLOY_DDL_TEST_DB_URL");
  private static final String USER = System.getenv("DEPLOY_DDL_TEST_DB_USER");
  private static final String PASSWORD = System.getenv("DEPLOY_DDL_TEST_DB_PASSWORD");
  private static final List<String> SCHEMAS = new ArrayList<>();
  @TempDir Path directory;

  record Database(String schema, String url) {
    JdbcTemplate jdbc() {
      return new JdbcTemplate(new DriverManagerDataSource(url, USER, PASSWORD));
    }

    Flyway flyway(String lineage) {
      return ConvergenceTool.configuration(url, USER, PASSWORD, schema, lineage).load();
    }

    Connection connection() throws Exception {
      Connection connection = DriverManager.getConnection(url, USER, PASSWORD);
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement(
              "select set_config('pastport.expected_database', current_database(), true), set_config('pastport.expected_schema', ?, true)")) {
        statement.setString(1, schema);
        statement.execute();
      }
      return connection;
    }
  }

  static Database database() {
    String schema = "convergence_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD))
        .execute("create schema " + schema);
    SCHEMAS.add(schema);
    return new Database(schema, URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema);
  }

  @AfterAll
  static void cleanup() {
    JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(URL, USER, PASSWORD));
    for (String schema : SCHEMAS) admin.execute("drop schema " + schema + " cascade");
  }

  static void finish(Database database, String lineage) {
    Flyway flyway = database.flyway(lineage);
    assertEquals(0, flyway.getConfiguration().getIgnoreMigrationPatterns().length);
    assertFalse(flyway.getConfiguration().isOutOfOrder());
    flyway.migrate();
    flyway.validate();
    assertEquals(0, flyway.migrate().migrationsExecuted);
    assertEquals(
        0,
        database
            .jdbc()
            .queryForObject(
                "select count(*) from pg_constraint where connamespace=current_schema()::regnamespace and not convalidated",
                Integer.class));
    assertEquals(
        0,
        database
            .jdbc()
            .queryForObject(
                "select count(*) from pg_index i join pg_class c on c.oid=i.indexrelid where c.relnamespace=current_schema()::regnamespace and (not indisvalid or not indisready)",
                Integer.class));
    assertNotNull(
        database
            .jdbc()
            .queryForObject("select to_regclass('user_profile_images')::text", String.class));
  }

  static void user(Database database) {
    database
        .jdbc()
        .execute(
            "insert into users(email,password_hash,nickname,role,created_at,updated_at) values ('synthetic@convergence.invalid','not-a-login','synthetic','USER',now(),now())");
  }

  static void product(Database database, long owner) {
    database
        .jdbc()
        .update(
            "insert into products(category,manufacturer,manufacturer_normalized,model_name,title,owner_id,serial_number_fingerprint,serial_number_ciphertext) values ('LAPTOP','Synthetic','SYNTHETIC','M','synthetic',?,'synthetic-db-fixture','synthetic-not-api-ciphertext')",
            owner);
  }

  static Database legacy() {
    Database database = database();
    ConvergenceTool.configuration(database.url(), USER, PASSWORD, database.schema(), "legacy")
        .target("2026103001")
        .load()
        .migrate();
    JdbcTemplate jdbc = database.jdbc();
    jdbc.execute(
        "update flyway_schema_history set version='2026102706',script='V2026102706__support_listing_summary_refresh.sql' where version='2026102701'");
    jdbc.execute(
        "update flyway_schema_history set version='2026102701',script='V2026102701__create_purchase_requests.sql' where version='2026102103'");
    jdbc.execute(
        "update flyway_schema_history set version='2026102702',script='V2026102702__create_ownership_tables.sql' where version='2026102803'");
    jdbc.execute(
        "update flyway_schema_history set version='2026102707',script='V2026102707__add_ownership_pending_slot.sql' where version='2026102804'");
    user(database);
    return database;
  }

  @Test
  void m1FreshDatedAndForeignKeysRejectOrphans() {
    Database database = database();
    finish(database, "dated");
    assertThrows(Exception.class, () -> product(database, 99999));
    user(database);
    product(database, database.jdbc().queryForObject("select id from users", Long.class));
    assertThrows(
        Exception.class,
        () ->
            database
                .jdbc()
                .execute(
                    "insert into documents(product_id,uploader_id,object_key,content_type,byte_size,mask_confirmed_at) values (99999,99999,'synthetic','application/pdf',1,now())"));
  }

  @Test
  void m2DemoRetainsItsValidOwners() {
    Database database = database();
    finish(database, "demo");
    assertEquals(4, database.jdbc().queryForObject("select count(*) from products", Integer.class));
    assertEquals(
        0,
        database
            .jdbc()
            .queryForObject(
                "select count(*) from products p left join users u on u.id=p.owner_id where u.id is null",
                Integer.class));
  }

  @Test
  void m3ExistingDatedDataIsPreserved() throws Exception {
    Database database = database();
    Flyway.configure()
        .dataSource(database.url(), USER, PASSWORD)
        .locations("classpath:db/migration", "classpath:db/deploy")
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .target("2026110401")
        .load()
        .migrate();
    user(database);
    product(database, database.jdbc().queryForObject("select id from users", Long.class));
    String before =
        database
            .jdbc()
            .queryForObject(
                "select md5(jsonb_agg(to_jsonb(p))::text) from products p", String.class);
    finish(database, "dated");
    assertEquals(
        before,
        database
            .jdbc()
            .queryForObject(
                "select md5(jsonb_agg(to_jsonb(p))::text) from products p", String.class));
    assertEquals(
        0,
        database
            .jdbc()
            .queryForObject(
                "select count(*) from flyway_schema_history where version in ('2','3','4','2026102103')",
                Integer.class));
  }

  @Test
  void documentDisplayNameMigratesAfterAppliedConvergence() {
    Database database = database();
    ConvergenceTool.configuration(database.url(), USER, PASSWORD, database.schema(), "dated")
        .target("2026110402")
        .load()
        .migrate();
    var history =
        database.jdbc().queryForList("select * from flyway_schema_history order by installed_rank");
    Flyway flyway = database.flyway("dated");
    assertFalse(flyway.getConfiguration().isOutOfOrder());
    assertEquals(0, flyway.getConfiguration().getIgnoreMigrationPatterns().length);
    assertEquals(1, flyway.migrate().migrationsExecuted);
    flyway.validate();
    assertEquals("2026110403", flyway.info().current().getVersion().toString());
    assertEquals(
        50,
        database
            .jdbc()
            .queryForObject(
                "select character_maximum_length from information_schema.columns where table_schema=current_schema() and table_name='documents' and column_name='display_name'",
                Integer.class));
    assertEquals(
        history,
        database
            .jdbc()
            .queryForList(
                "select * from flyway_schema_history where version<>'2026110403' order by installed_rank"));
    assertEquals(0, flyway.migrate().migrationsExecuted);
  }

  @Test
  void m4PendingChainIsSeparateAndStrictlyResolved() {
    Database database = database();
    ConvergenceTool.configuration(database.url(), USER, PASSWORD, database.schema(), "dated")
        .locations("classpath:db/migration")
        .target("2026110202")
        .load()
        .migrate();
    ConvergenceTool.configuration(database.url(), USER, PASSWORD, database.schema(), "pending")
        .locations(
            "classpath:db/migration",
            "classpath:db/pending/trade-p2",
            "classpath:db/pending/trade-p3")
        .target("2026110202")
        .outOfOrder(true)
        .load()
        .migrate();
    finish(database, "pending");
    assertThrows(Exception.class, () -> database.flyway("dated").validate());
  }

  @Test
  void m5SnapshotPatternNormalizesPurgesAndConverges() throws Exception {
    Database database = legacy();
    product(database, 99999);
    assertTrue(
        assertThrows(Exception.class, () -> database.flyway("legacy").validate())
            .getMessage()
            .contains("checksum"));
    var before =
        database
            .jdbc()
            .queryForList(
                "select installed_rank,description,type,checksum,success,execution_time from flyway_schema_history order by installed_rank");
    try (Connection connection = database.connection()) {
      ConvergenceTool.verifyHistory(connection, database.flyway("legacy"), true);
      ConvergenceTool.normalize(connection);
      connection.commit();
    }
    assertEquals(
        before,
        database
            .jdbc()
            .queryForList(
                "select installed_rank,description,type,checksum,success,execution_time from flyway_schema_history order by installed_rank"));
    try (Connection connection = database.connection()) {
      String state = ConvergenceTool.snapshot(connection, "synthetic-target");
      ConvergenceTool.normalize(connection);
      assertEquals(state, ConvergenceTool.snapshot(connection, "synthetic-target"));
      String plan = ConvergenceTool.plan(connection, "synthetic-target", "PURGE");
      ConvergenceTool.approve(
          connection, plan, ConvergenceTool.hash(plan), "synthetic-target", "PURGE");
      ConvergenceTool.purge(connection);
      connection.commit();
    }
    finish(database, "legacy");
    assertEquals(1, database.jdbc().queryForObject("select count(*) from users", Integer.class));
    assertEquals(0, database.jdbc().queryForObject("select count(*) from products", Integer.class));
  }

  @Test
  void m6NormalizationFailureRollsBackEarlierOwnershipChanges() throws Exception {
    Database database = legacy();
    database
        .jdbc()
        .execute("update flyway_schema_history set checksum=0 where version='2026102706'");
    String before;
    try (Connection connection = database.connection()) {
      before = ConvergenceTool.snapshot(connection, "synthetic-target");
    }
    try (Connection connection = database.connection()) {
      assertTrue(
          assertThrows(Exception.class, () -> ConvergenceTool.normalize(connection))
              .getMessage()
              .contains("identity/checksum"));
      connection.rollback();
      assertEquals(before, ConvergenceTool.snapshot(connection, "synthetic-target"));
    }
  }

  @Test
  void m6PurgeRequiresExactApprovalAndDetectsTargetContentDrift() throws Exception {
    Database database = database();
    finish(database, "dated");
    user(database);
    try (Connection connection = database.connection()) {
      String plan = ConvergenceTool.plan(connection, "synthetic-target", "PURGE");
      for (String invalid :
          List.of(
              plan.replaceFirst("created=[^\\n]+", "created=2000-01-01T00:00:00Z"),
              plan.replaceFirst("source=[^\\n]+", "source=changed"),
              plan.replaceFirst("catalog=[^\\n]+", "catalog=changed"))) {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ConvergenceTool.approve(
                    connection,
                    invalid,
                    ConvergenceTool.hash(invalid),
                    "synthetic-target",
                    "PURGE"));
        connection.rollback();
      }
      assertThrows(
          IllegalArgumentException.class,
          () -> ConvergenceTool.approve(connection, plan, "", "synthetic-target", "PURGE"));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ConvergenceTool.approve(
                  connection, plan, ConvergenceTool.hash(plan), "other-target", "PURGE"));
      connection.rollback();
      product(database, database.jdbc().queryForObject("select id from users", Long.class));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ConvergenceTool.approve(
                  connection, plan, ConvergenceTool.hash(plan), "synthetic-target", "PURGE"));
      connection.rollback();
    }
    assertEquals(1, database.jdbc().queryForObject("select count(*) from products", Integer.class));
  }

  @Test
  void m6UnknownTablesAndMidDeleteFailureRollBack() throws Exception {
    Database database = database();
    finish(database, "dated");
    user(database);
    product(database, database.jdbc().queryForObject("select id from users", Long.class));
    database.jdbc().execute("create table unapproved_table(id bigint)");
    try (Connection connection = database.connection()) {
      assertThrows(Exception.class, () -> ConvergenceTool.purge(connection));
      connection.rollback();
    }
    database.jdbc().execute("drop table unapproved_table");
    try (Connection connection = database.connection()) {
      String before = ConvergenceTool.snapshot(connection, "synthetic-target");
      ConvergenceTool.purge(connection);
      assertThrows(Exception.class, () -> connection.createStatement().execute("select 1/0"));
      connection.rollback();
      assertEquals(before, ConvergenceTool.snapshot(connection, "synthetic-target"));
    }
    database
        .jdbc()
        .execute(
            "insert into user_profile_images(user_id,content,content_type,updated_at) select id,decode('00','hex'),'image/jpeg',now() from users");
    database
        .jdbc()
        .execute(
            "alter table user_profile_images add column test_product_id bigint references products(id)");
    database
        .jdbc()
        .execute("update user_profile_images set test_product_id=(select id from products)");
    try (Connection connection = database.connection()) {
      String before = ConvergenceTool.snapshot(connection, "synthetic-target");
      assertThrows(Exception.class, () -> ConvergenceTool.purge(connection));
      connection.rollback();
      assertEquals(before, ConvergenceTool.snapshot(connection, "synthetic-target"));
    }
  }

  @Test
  void purgePreservesUsersProfileBytesAndHistoryOnSuccess() throws Exception {
    Database database = database();
    finish(database, "dated");
    user(database);
    product(database, database.jdbc().queryForObject("select id from users", Long.class));
    database
        .jdbc()
        .execute(
            "insert into user_profile_images(user_id,content,content_type,updated_at) select id,decode('000102','hex'),'image/jpeg',now() from users");
    try (Connection connection = database.connection()) {
      List<String> before =
          ConvergenceTool.snapshot(connection, "synthetic-target")
              .lines()
              .filter(line -> line.startsWith("PRESERVE "))
              .toList();
      String plan = ConvergenceTool.plan(connection, "synthetic-target", "PURGE");
      ConvergenceTool.approve(
          connection, plan, ConvergenceTool.hash(plan), "synthetic-target", "PURGE");
      ConvergenceTool.purge(connection);
      connection.commit();
      assertEquals(
          before,
          ConvergenceTool.snapshot(connection, "synthetic-target")
              .lines()
              .filter(line -> line.startsWith("PRESERVE "))
              .toList());
    }
    assertEquals(0, database.jdbc().queryForObject("select count(*) from products", Integer.class));
  }

  @Test
  void cliOperationApprovalsRejectCrossPurposeAndMalformedPlans() throws Exception {
    Database database = legacy();
    product(database, 99999);
    Path planFile = directory.resolve("approval.txt");
    Map<String, String> environment =
        new HashMap<>(
            Map.of(
                "CONVERGENCE_DB_URL", database.url(),
                "CONVERGENCE_DB_USER", USER,
                "CONVERGENCE_DB_PASSWORD", PASSWORD,
                "CONVERGENCE_SCHEMA", database.schema(),
                "CONVERGENCE_DATABASE",
                    database.jdbc().queryForObject("select current_database()", String.class),
                "CONVERGENCE_TARGET_ID", "synthetic-cli",
                "CONVERGENCE_URL_SHA256", ConvergenceTool.hash(database.url()),
                "CONVERGENCE_PLAN", planFile.toString()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ConvergenceTool.run(new String[] {"plan", "legacy"}, environment));
    assertThrows(
        IllegalArgumentException.class,
        () -> ConvergenceTool.run(new String[] {"plan", "legacy", "UNKNOWN"}, environment));
    String before;
    try (Connection connection = database.connection()) {
      before = ConvergenceTool.snapshot(connection, "unchanged");
    }
    for (String operation : List.of("NORMALIZE", "PURGE")) {
      ConvergenceTool.run(new String[] {"plan", "legacy", operation}, environment);
      String plan = Files.readString(planFile);
      environment.put("CONVERGENCE_APPROVED_PLAN_SHA256", ConvergenceTool.hash(plan));
      String wrongAction = operation.equals("NORMALIZE") ? "purge" : "normalize";
      assertTrue(
          assertThrows(
                  IllegalArgumentException.class,
                  () -> ConvergenceTool.run(new String[] {wrongAction, "legacy"}, environment))
              .getMessage()
              .contains("operation"));
      for (String malformed :
          List.of(
              plan.replace("operation=" + operation + "\n", ""),
              plan.replace("operation=" + operation, "operation=UNKNOWN"),
              plan.replace(
                  "operation=" + operation,
                  "operation=" + operation + "\noperation=" + operation))) {
        Files.writeString(planFile, malformed);
        environment.put("CONVERGENCE_APPROVED_PLAN_SHA256", ConvergenceTool.hash(malformed));
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ConvergenceTool.run(
                    new String[] {operation.toLowerCase(java.util.Locale.ROOT), "legacy"},
                    environment));
      }
      try (Connection connection = database.connection()) {
        assertEquals(before, ConvergenceTool.snapshot(connection, "unchanged"));
      }
    }
    ConvergenceTool.run(new String[] {"plan", "legacy", "NORMALIZE"}, environment);
    environment.put(
        "CONVERGENCE_APPROVED_PLAN_SHA256", ConvergenceTool.hash(Files.readString(planFile)));
    ConvergenceTool.run(new String[] {"normalize", "legacy"}, environment);
    assertEquals(
        4,
        database
            .jdbc()
            .queryForObject(
                "select count(*) from flyway_schema_history where version in ('2026102103','2026102701','2026102803','2026102804')",
                Integer.class));
    ConvergenceTool.run(new String[] {"plan", "legacy", "NORMALIZE"}, environment);
    environment.put(
        "CONVERGENCE_APPROVED_PLAN_SHA256", ConvergenceTool.hash(Files.readString(planFile)));
    ConvergenceTool.run(new String[] {"normalize", "legacy"}, environment);
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> ConvergenceTool.run(new String[] {"purge", "legacy"}, environment))
            .getMessage()
            .contains("operation"));
    assertEquals(1, database.jdbc().queryForObject("select count(*) from products", Integer.class));
    ConvergenceTool.run(new String[] {"plan", "legacy", "PURGE"}, environment);
    environment.put(
        "CONVERGENCE_APPROVED_PLAN_SHA256", ConvergenceTool.hash(Files.readString(planFile)));
    ConvergenceTool.run(new String[] {"purge", "legacy"}, environment);
    assertEquals(0, database.jdbc().queryForObject("select count(*) from products", Integer.class));
    assertEquals(1, database.jdbc().queryForObject("select count(*) from users", Integer.class));
  }

  @Test
  void m6MissingParentColumnIsReportedAsNotRun() throws Exception {
    Database database = database();
    database.jdbc().execute("create table products(owner_id bigint)");
    try (Connection connection = database.connection()) {
      assertTrue(
          ConvergenceTool.audit(connection)
              .contains("products/owner_id/users/id=NOT_RUN/MISSING_PREREQUISITE"));
    }
  }

  @Test
  void m6CatalogAndTypeGuardsRejectTampering() throws Exception {
    Database database = database();
    ConvergenceTool.configuration(database.url(), USER, PASSWORD, database.schema(), "dated")
        .target("2026110401")
        .load()
        .migrate();
    database.jdbc().execute("drop index ix_products_owner_archive_created");
    database.jdbc().execute("create index ix_products_owner_archive_created on products(id)");
    assertTrue(
        assertThrows(Exception.class, () -> database.flyway("dated").migrate())
            .getMessage()
            .contains("unexpected definition"));
    assertEquals(
        0,
        database
            .jdbc()
            .queryForObject(
                "select count(*) from pg_constraint where conrelid='products'::regclass and conname='fk_products_owner'",
                Integer.class));
    database.jdbc().execute("drop index ix_products_owner_archive_created");
    database
        .jdbc()
        .execute(
            "create index ix_products_owner_archive_created on products(owner_id,archived_at,created_at desc,id desc)");
    finish(database, "dated");
    user(database);
    long owner = database.jdbc().queryForObject("select id from users", Long.class);
    product(database, owner);
    long product = database.jdbc().queryForObject("select id from products", Long.class);
    for (String type : List.of("DIRECT", "INTERNAL")) {
      database
          .jdbc()
          .update(
              "insert into ownership_transfers(product_id,from_user_id,token_hash,status,transfer_type,expires_at) values (?, ?, ?, 'EXPIRED', ?, now())",
              product,
              owner,
              type,
              type);
    }
    assertThrows(
        Exception.class,
        () -> database.jdbc().update("update ownership_transfers set transfer_type='UNKNOWN'"));
    assertThrows(
        Exception.class,
        () -> database.jdbc().update("update ownership_transfers set transfer_type=null"));
  }

  @Test
  void m6HistoryIdentityAndIgnorePoliciesAreMeasured() throws Exception {
    Database database = database();
    finish(database, "dated");
    for (String mutation :
        List.of("checksum=0", "type='JDBC'", "description='unexpected description'")) {
      var original =
          database
              .jdbc()
              .queryForMap(
                  "select checksum,type,description from flyway_schema_history where version='5'");
      database
          .jdbc()
          .execute("update flyway_schema_history set " + mutation + " where version='5'");
      assertFalse(database.flyway("dated").validateWithResult().validationSuccessful);
      Flyway broad =
          ConvergenceTool.configuration(database.url(), USER, PASSWORD, database.schema(), "dated")
              .ignoreMigrationPatterns("*:*")
              .load();
      System.out.println(
          "Flyway policy probe "
              + mutation.split("=")[0]
              + ": empty=false wildcard="
              + broad.validateWithResult().validationSuccessful);
      try (Connection connection = database.connection()) {
        assertThrows(
            Exception.class,
            () -> ConvergenceTool.verifyHistory(connection, database.flyway("dated"), false));
      }
      database
          .jdbc()
          .update(
              "update flyway_schema_history set checksum=?,type=?,description=? where version='5'",
              original.get("checksum"),
              original.get("type"),
              original.get("description"));
    }
    database
        .jdbc()
        .execute(
            "insert into flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) select max(installed_rank)+1,'9999999999','future synthetic','SQL','V9999999999__future_synthetic.sql',1,current_user,0,true from flyway_schema_history");
    assertFalse(database.flyway("dated").validateWithResult().validationSuccessful);
    assertTrue(
        ConvergenceTool.configuration(database.url(), USER, PASSWORD, database.schema(), "dated")
            .ignoreMigrationPatterns("*:future")
            .load()
            .validateWithResult()
            .validationSuccessful);
  }

  @Test
  void m6NormalizationRejectsPartialAndWrongCatalog() throws Exception {
    for (String mutation :
        List.of(
            "alter table ownership_transfers drop constraint ck_transfers_status; alter table ownership_transfers add constraint ck_transfers_status check(true)",
            "update flyway_schema_history set version='2026102103',script='V2026102103__create_purchase_requests.sql' where version='2026102701'",
            "insert into flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) select (select max(installed_rank)+1 from flyway_schema_history),'2026102803',description,type,'V2026102803__create_ownership_tables.sql',checksum,installed_by,execution_time,success from flyway_schema_history where version='2026102702'")) {
      Database database = legacy();
      database.jdbc().execute(mutation);
      try (Connection connection = database.connection()) {
        String before = ConvergenceTool.snapshot(connection, "synthetic-target");
        Exception failure =
            assertThrows(Exception.class, () -> ConvergenceTool.normalize(connection));
        assertTrue(
            failure.getMessage().contains("catalog definition")
                || failure.getMessage().contains("collision")
                || failure.getMessage().contains("recorded"));
        connection.rollback();
        assertEquals(before, ConvergenceTool.snapshot(connection, "synthetic-target"));
      }
    }
  }
}
