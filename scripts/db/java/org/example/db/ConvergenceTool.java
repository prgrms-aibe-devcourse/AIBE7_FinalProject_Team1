package org.example.db;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Explicit, offline-capable maintenance runner. Never starts Spring or loads dotenv. */
public final class ConvergenceTool {
  public static final List<String> PURGE =
      List.of(
          "transaction_change_reviews",
          "event_documents",
          "document_extractions",
          "document_replacements",
          "audit_logs",
          "product_events",
          "ownership_transfers",
          "purchase_requests",
          "chat_read_positions",
          "chat_messages",
          "chat_rooms",
          "listing_images",
          "listings",
          "draft_images",
          "listing_drafts",
          "product_field_authors",
          "product_images",
          "laptop_details",
          "documents",
          "products");
  public static final List<String> PRESERVE =
      List.of("users", "user_profile_images", "flyway_schema_history");

  private ConvergenceTool() {}

  public static String[] locations(String lineage) {
    List<String> paths =
        new ArrayList<>(
            List.of("classpath:db/migration", "classpath:db/deploy", "classpath:db/convergence"));
    switch (lineage) {
      case "dated" -> {}
      case "legacy" ->
          paths.addAll(List.of("classpath:db/legacy", "classpath:db/compatibility/purchase"));
      case "pending" ->
          paths.addAll(List.of("classpath:db/pending/trade-p2", "classpath:db/pending/trade-p3"));
      case "demo" -> paths.add("classpath:db/demo/sale-draft");
      default -> throw new IllegalArgumentException("Unknown lineage");
    }
    return paths.toArray(String[]::new);
  }

  public static FluentConfiguration configuration(
      String url, String user, String password, String schema, String lineage) {
    return Flyway.configure()
        .dataSource(url, user, password)
        .schemas(schema)
        .defaultSchema(schema)
        .locations(locations(lineage))
        .ignoreMigrationPatterns(new String[0])
        .outOfOrder(false)
        .cleanDisabled(true)
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
  }

  public static String hash(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private static String required(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
    return value;
  }

  private static String scalar(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      if (!rows.next()) throw new SQLException("No query result");
      return rows.getString(1);
    }
  }

  private static List<String> tables(Connection connection) throws SQLException {
    List<String> result = new ArrayList<>();
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select tablename from pg_tables where schemaname = current_schema() order by tablename")) {
      while (rows.next()) result.add(rows.getString(1));
    }
    return result;
  }

  private static String identifier(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  /** Read-only content fingerprint. Only counts/digests leave the connection. */
  public static String snapshot(Connection connection, String target) throws Exception {
    StringBuilder result = new StringBuilder("target=" + target + "\n");
    result.append("source=").append(sourceDigest()).append('\n');
    result.append("database=").append(scalar(connection, "select current_database()"));
    result.append("\nschema=").append(scalar(connection, "select current_schema()"));
    for (String table : tables(connection)) {
      result
          .append("\n")
          .append(PURGE.contains(table) ? "DELETE " : "PRESERVE ")
          .append(table)
          .append("=");
      result.append(fingerprint(connection, table));
    }
    for (String sql :
        List.of(
            "select table_name,column_name,data_type,is_nullable,column_default from information_schema.columns where table_schema=current_schema() order by table_name,ordinal_position",
            "select c.relname,k.conname,k.contype,k.convalidated,pg_get_constraintdef(k.oid) definition from pg_constraint k join pg_class c on c.oid=k.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() order by c.relname,k.conname",
            "select tablename,indexname,indexdef from pg_indexes where schemaname=current_schema() order by tablename,indexname")) {
      result
          .append("\ncatalog=")
          .append(
              hash(
                  scalar(
                      connection,
                      "select coalesce(jsonb_agg(to_jsonb(s))::text,'[]') from (" + sql + ") s")));
    }
    return result.append('\n').toString();
  }

  /** Refuses unknown tables and user triggers before any approved deletion. */
  public static void purge(Connection connection) throws Exception {
    List<String> present = tables(connection);
    Map<String, String> preserved = new LinkedHashMap<>();
    for (String table : PRESERVE)
      if (present.contains(table)) preserved.put(table, fingerprint(connection, table));
    if (present.stream().anyMatch(table -> !PURGE.contains(table) && !PRESERVE.contains(table))) {
      throw new SQLException("Unknown table: deletion scope requires review");
    }
    if (!"0"
        .equals(
            scalar(
                connection,
                "select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid "
                    + "join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() and not t.tgisinternal"))) {
      throw new SQLException("User trigger requires review");
    }
    if (!"0"
        .equals(
            scalar(
                connection,
                "select count(*) from pg_rewrite r join pg_class c on c.oid=r.ev_class "
                    + "where c.relnamespace=current_schema()::regnamespace and c.relkind='r'")))
      throw new SQLException("Table rewrite rule requires review");
    if (!"0"
        .equals(
            scalar(
                connection,
                "select count(*) from pg_class c where c.relnamespace=current_schema()::regnamespace "
                    + "and c.relrowsecurity and not exists (select 1 from pg_roles where rolname=current_user and (rolsuper or rolbypassrls))")))
      throw new SQLException("Maintenance role must see all rows despite RLS");
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select child.relname, ns.nspname, parent.relname from pg_constraint k join pg_class child on child.oid=k.conrelid "
                    + "join pg_namespace ns on ns.oid=child.relnamespace join pg_class parent on parent.oid=k.confrelid "
                    + "where k.contype='f' and parent.relnamespace=current_schema()::regnamespace")) {
      while (rows.next()) {
        if (PURGE.contains(rows.getString(3))
            && (!rows.getString(2).equals(connection.getSchema())
                || !PURGE.contains(rows.getString(1)))) {
          throw new SQLException(
              "Incoming reference from a preserved table/schema requires review");
        }
      }
    }
    try (var statement = connection.createStatement()) {
      for (String table : PURGE)
        if (present.contains(table)) statement.executeUpdate("delete from " + identifier(table));
    }
    for (String table : PURGE)
      if (present.contains(table)
          && !"0".equals(scalar(connection, "select count(*) from " + identifier(table))))
        throw new SQLException("Purge left unapproved residual rows");
    for (var entry : preserved.entrySet())
      if (!entry.getValue().equals(fingerprint(connection, entry.getKey())))
        throw new SQLException("Preserved data changed; rollback required");
  }

  private static String fingerprint(Connection connection, String table) throws SQLException {
    return scalar(
        connection,
        "select count(*) || ':' || md5(coalesce(string_agg(d, '' order by d), '')) from "
            + "(select md5(to_jsonb(t)::text) d from "
            + identifier(table)
            + " t) s");
  }

  /** Both normalizers share one transaction; any later guard failure rolls back earlier changes. */
  public static void normalize(Connection connection) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.execute(Files.readString(Path.of("scripts/db/verify-normalization-catalog.sql")));
      statement.execute(Files.readString(Path.of("scripts/db/normalize-ownership-version.sql")));
      statement.execute(Files.readString(Path.of("scripts/db/normalize-purchase-summary.sql")));
    }
  }

  public static List<String> audit(Connection connection) throws Exception {
    List<String> results = new ArrayList<>();
    for (String[] relation :
        new String[][] {
          {"products", "owner_id", "users", "id"},
          {"documents", "product_id", "products", "id"},
          {"documents", "uploader_id", "users", "id"},
          {"chat_rooms", "seller_id", "users", "id"},
          {"chat_rooms", "inquirer_id", "users", "id"},
          {"chat_messages", "sender_id", "users", "id"},
          {"ownership_transfers", "product_id", "products", "id"},
          {"ownership_transfers", "from_user_id", "users", "id"},
          {"ownership_transfers", "to_user_id", "users", "id"}
        }) {
      String label = String.join("/", relation);
      if (!columnExists(connection, relation[0], relation[1])
          || !columnExists(connection, relation[2], relation[3])) {
        results.add(label + "=NOT_RUN/MISSING_PREREQUISITE");
      } else {
        results.add(
            label
                + "="
                + scalar(
                    connection,
                    "select count(*) from "
                        + identifier(relation[0])
                        + " c where c."
                        + identifier(relation[1])
                        + " is not null and not exists (select 1 from "
                        + identifier(relation[2])
                        + " p where p."
                        + identifier(relation[3])
                        + "=c."
                        + identifier(relation[1])
                        + ")"));
      }
    }
    for (String[] rule :
        new String[][] {
          {
            "products",
            "owner_id,serial_number_ciphertext",
            "owner_id is not null and owner_id>0 and serial_number_ciphertext is not null"
          },
          {
            "ownership_transfers",
            "transfer_type",
            "transfer_type is not null and transfer_type in ('DIRECT','INTERNAL')"
          },
          {
            "ownership_transfers",
            "status,pending_slot",
            "(status='PENDING' and pending_slot=1) or (status<>'PENDING' and pending_slot is null)"
          }
        }) {
      boolean ready = true;
      for (String column : rule[1].split(",")) ready &= columnExists(connection, rule[0], column);
      results.add(
          rule[0]
              + "/business/"
              + rule[1]
              + "="
              + (ready
                  ? scalar(
                      connection,
                      "select count(*) from "
                          + identifier(rule[0])
                          + " where ("
                          + rule[2]
                          + ") is not true")
                  : "NOT_RUN/MISSING_PREREQUISITE"));
    }
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "select r.relname,c.conname,pg_get_expr(c.conbin,c.conrelid) from pg_constraint c join pg_class r on r.oid=c.conrelid "
                    + "join pg_namespace n on n.oid=r.relnamespace where n.nspname=current_schema() and c.contype='c' order by r.relname,c.conname")) {
      while (rows.next())
        results.add(
            "CHECK/"
                + rows.getString(2)
                + "="
                + scalar(
                    connection,
                    "select count(*) from "
                        + identifier(rows.getString(1))
                        + " where ("
                        + rows.getString(3)
                        + ") is false"));
    }
    if (tables(connection).contains("flyway_schema_history"))
      results.add(
          "history="
              + scalar(
                  connection,
                  "select jsonb_agg(to_jsonb(h))::text from (select installed_rank,version,description,type,script,checksum,success from flyway_schema_history order by installed_rank) h"));
    else results.add("history=NOT_RUN/MISSING_PREREQUISITE");
    results.add(
        "Cross-product/participant business relations require API acceptance; FK counts alone do not prove them");
    return results;
  }

  private static boolean columnExists(Connection connection, String table, String column)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "select count(*) from information_schema.columns where table_schema=current_schema() and table_name=? and column_name=?")) {
      statement.setString(1, table);
      statement.setString(2, column);
      try (var rows = statement.executeQuery()) {
        rows.next();
        return rows.getInt(1) == 1;
      }
    }
  }

  private static void lockTables(Connection connection) throws SQLException {
    try (var statement = connection.createStatement()) {
      for (String table : tables(connection))
        statement.execute("lock table " + identifier(table) + " in access exclusive mode");
    }
  }

  public static void approve(Connection connection, String plan, String approvedHash, String target)
      throws Exception {
    if (!hash(plan).equals(approvedHash))
      throw new IllegalArgumentException("Plan approval differs");
    Instant created = Instant.parse(plan.substring(8, plan.indexOf('\n')));
    if (created.isBefore(Instant.now().minusSeconds(1800)) || created.isAfter(Instant.now()))
      throw new IllegalArgumentException("Plan expired");
    lockTables(connection);
    if (!plan.substring(plan.indexOf('\n') + 1).equals(snapshot(connection, target)))
      throw new IllegalArgumentException("Plan drift; no writes permitted");
  }

  public static void verifyHistory(Connection connection, Flyway flyway, boolean oldVersions)
      throws SQLException {
    Map<String, MigrationInfo> resolved = new LinkedHashMap<>();
    Flyway resolver =
        Flyway.configure()
            .dataSource("jdbc:h2:mem:convergence_resolver_" + java.util.UUID.randomUUID(), "sa", "")
            .locations(flyway.getConfiguration().getLocations())
            .ignoreMigrationPatterns(new String[0])
            .load();
    for (MigrationInfo info : resolver.info().all()) {
      if (info.getVersion() != null && info.getPhysicalLocation() != null)
        resolved.put(info.getVersion().getVersion(), info);
    }
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery("select * from flyway_schema_history order by installed_rank")) {
      List<String> seen = new ArrayList<>();
      while (rows.next()) {
        String version = rows.getString("version");
        String script = rows.getString("script");
        if (oldVersions) {
          if ("2026102701".equals(version)
              && "V2026102701__create_purchase_requests.sql".equals(script)) version = "2026102103";
          else if ("2026102706".equals(version)) version = "2026102701";
          else if ("2026102702".equals(version)) version = "2026102803";
          else if ("2026102707".equals(version)) version = "2026102804";
          script = script.replaceFirst("^V[0-9]+__", "V" + version + "__");
        }
        MigrationInfo info = resolved.get(version);
        if (info == null
            || seen.contains(version)
            || !rows.getBoolean("success")
            || !info.getScript().equals(script)
            || !info.getDescription().equals(rows.getString("description"))
            || !info.getType().name().equals(rows.getString("type"))
            || !java.util.Objects.equals(info.getChecksum(), rows.getObject("checksum"))) {
          throw new SQLException(
              "Unrecognized history identity/checksum/status at version " + version);
        }
        seen.add(version);
      }
    }
  }

  private static String sourceDigest() throws Exception {
    StringBuilder result = new StringBuilder();
    for (String directory : List.of("src/main/resources/db", "src/main/java/db", "scripts/db")) {
      try (var files = Files.walk(Path.of(directory))) {
        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
          result
              .append(file.toString().replace('\\', '/'))
              .append(':')
              .append(
                  HexFormat.of()
                      .formatHex(
                          MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))))
              .append('\n');
        }
      }
    }
    return hash(result.toString());
  }

  private static void manifest(Flyway flyway) throws Exception {
    var version = new java.util.Properties();
    try (var resource =
        Flyway.class.getResourceAsStream(
            "/META-INF/maven/org.flywaydb/flyway-core/pom.properties")) {
      if (resource == null)
        throw new IllegalStateException("Flyway dependency version metadata missing");
      version.load(resource);
    }
    System.out.println("Flyway=" + version.getProperty("version"));
    System.out.println("locations=" + Arrays.toString(flyway.getConfiguration().getLocations()));
    System.out.println(
        "ignore=" + Arrays.toString(flyway.getConfiguration().getIgnoreMigrationPatterns()));
    System.out.println("outOfOrder=" + flyway.getConfiguration().isOutOfOrder());
    for (MigrationInfo info : flyway.info().all()) {
      System.out.printf(
          "%s\t%s\t%s\t%s\t%s\t%s%n",
          info.getVersion(),
          info.getType(),
          info.getDescription(),
          info.getScript(),
          info.getChecksum(),
          info.getState());
    }
    for (String directory : List.of("src/main/resources/db", "src/main/java/db", "scripts/db")) {
      try (var files = Files.walk(Path.of(directory))) {
        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
          System.out.println(
              HexFormat.of()
                      .formatHex(
                          MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
                  + "  "
                  + file.toString().replace('\\', '/'));
        }
      }
    }
  }

  public static void main(String[] args) throws Exception {
    String action = args[0];
    String lineage = args[1];
    if (!List.of("check", "manifest", "plan", "purge", "normalize", "migrate").contains(action))
      throw new IllegalArgumentException("Unknown action");
    String url = required("CONVERGENCE_DB_URL");
    String schema = required("CONVERGENCE_SCHEMA");
    if (!schema.matches("[a-z][a-z0-9_]*")
        || schema.startsWith("pg_")
        || schema.equals("information_schema"))
      throw new IllegalArgumentException("Invalid application schema");
    if (!hash(url).equals(required("CONVERGENCE_URL_SHA256")))
      throw new IllegalArgumentException("Connection target digest differs");
    String user = required("CONVERGENCE_DB_USER");
    String password = required("CONVERGENCE_DB_PASSWORD");
    Flyway flyway = configuration(url, user, password, schema, lineage).load();
    try (Connection connection = DriverManager.getConnection(url, user, password)) {
      connection.setSchema(schema);
      connection.setAutoCommit(false);
      if (!List.of("normalize", "purge", "migrate").contains(action))
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setReadOnly(!List.of("normalize", "purge", "migrate").contains(action));
      try (var statement = connection.createStatement()) {
        statement.execute("set local lock_timeout='5s'");
        statement.execute("set local statement_timeout='60s'");
      }
      if (!required("CONVERGENCE_DATABASE").equals(scalar(connection, "select current_database()"))
          || !schema.equals(scalar(connection, "select current_schema()")))
        throw new IllegalArgumentException("Database/schema differs");
      String target =
          hash(required("CONVERGENCE_TARGET_ID") + "\n" + url + "\n" + schema + "\n" + lineage);
      if (action.equals("manifest")) {
        manifest(flyway);
      } else if (action.equals("check")) {
        System.out.print(snapshot(connection, target));
        for (String result : audit(connection)) System.out.println(result);
        try (var statement = connection.createStatement()) {
          statement.execute(Files.readString(Path.of("scripts/db/check-convergence.sql")));
          do {
            try (var rows = statement.getResultSet()) {
              if (rows != null) while (rows.next()) System.out.println(rows.getString(1));
            }
          } while (statement.getMoreResults());
        }
      } else if (action.equals("plan")) {
        String plan = "created=" + Instant.now() + "\n" + snapshot(connection, target);
        Files.writeString(Path.of(required("CONVERGENCE_PLAN")), plan);
        System.out.println("plan_sha256=" + hash(plan));
      } else if (action.equals("normalize") || action.equals("purge")) {
        String plan = Files.readString(Path.of(required("CONVERGENCE_PLAN")));
        if (action.equals("normalize")) verifyHistory(connection, flyway, true);
        approve(connection, plan, required("CONVERGENCE_APPROVED_PLAN_SHA256"), target);
        if (action.equals("normalize")) {
          if (!lineage.equals("legacy"))
            throw new IllegalArgumentException("Normalization requires legacy lineage");
          try (var statement =
              connection.prepareStatement(
                  "select set_config('pastport.expected_database', ?, true), set_config('pastport.expected_schema', ?, true)")) {
            statement.setString(1, required("CONVERGENCE_DATABASE"));
            statement.setString(2, schema);
            statement.execute();
          }
          normalize(connection);
        } else {
          purge(connection);
        }
        connection.commit();
        System.out.println(action + " committed; generate a fresh plan before another write step");
      } else {
        connection.rollback();
        if (lineage.equals("legacy")) {
          String count =
              scalar(
                  connection,
                  "select count(*) from flyway_schema_history where version in ('2','3','4','2026102103') and success");
          if (!count.equals("4"))
            throw new IllegalArgumentException(
                "Legacy location is only for an already normalized legacy DB");
        }
        connection.rollback();
        var result = flyway.migrate();
        flyway.validate();
        System.out.println("migrations_executed=" + result.migrationsExecuted);
        if (flyway.migrate().migrationsExecuted != 0)
          throw new IllegalStateException("Second migration run must be empty");
      }
      connection.rollback();
    }
  }
}
