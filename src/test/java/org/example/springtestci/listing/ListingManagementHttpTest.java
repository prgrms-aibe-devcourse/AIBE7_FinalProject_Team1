package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.common.error.*;
import org.example.springtestci.listing.service.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP·JWT·운영 마이그레이션을 사용한다. 외부 거래·이력 변경만 같은 DB의 테스트 모형이다. */
class ListingManagementHttpTest {
  private static final String SECRET = "r07-http-test-only-signing-secret-at-least-32-bytes";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final AtomicBoolean FAIL_TRADE = new AtomicBoolean(),
      FAIL_HISTORY = new AtomicBoolean();
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static String baseUrl, schema;
  private static Path storageRoot;

  @TestConfiguration(proxyBeanMethods = false)
  static class EffectsFixture {
    @Bean
    ListingHistoryReader managementHistoryFixture() {
      return productId ->
          new ListingHistoryReader.Snapshot(ListingHistoryReader.State.READY, false, List.of());
    }

    @Bean
    ListingTradeCanceller managementTradeFixture(JdbcTemplate jdbc) {
      return command -> {
        jdbc.update(
            "UPDATE r07_trade_probe SET request_state='CANCELLED', trade_state=CASE WHEN trade_state='RESERVED' THEN 'CANCELLED' ELSE trade_state END WHERE listing_id=? AND request_state IN ('PENDING','ACCEPTED')",
            command.listingId());
        jdbc.update(
            "UPDATE r07_transfer_probe SET state='CANCELLED',token_valid=FALSE WHERE listing_id=? AND state='PENDING'",
            command.listingId());
        jdbc.update(
            "UPDATE r07_history_probe SET cancel_calls=cancel_calls+1 WHERE product_id=?",
            command.productId());
        if (FAIL_TRADE.get()) throw new CommonException(ErrorCode.LISTING_CANCEL_UNAVAILABLE);
      };
    }

    @Bean
    ListingHistoryPrivatizer managementHistoryPrivatizer(JdbcTemplate jdbc) {
      return (productId, listingId, sellerId) -> {
        jdbc.update(
            "UPDATE r07_history_probe SET visibility='PRIVATE',history_calls=history_calls+1 WHERE product_id=?",
            productId);
        if (FAIL_HISTORY.get()) throw new CommonException(ErrorCode.LISTING_CANCEL_UNAVAILABLE);
      };
    }
  }

  @BeforeAll
  static void start() throws Exception {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    boolean postgres = url != null && !url.isBlank();
    if (postgres && !url.contains("sale_ai_test"))
      throw new IllegalStateException("Use a dedicated sale_ai_test database only");
    schema = postgres ? "listing_manage_r07_" + Long.toUnsignedString(System.nanoTime()) : "public";
    if (postgres) url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    else
      url =
          "jdbc:h2:mem:listing_manage_r07;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    storageRoot = Files.createTempDirectory("listing-management-http-test-");
    for (String name : List.of("front", "side", "back", "thinkpad", "other")) {
      try (var image =
          new ClassPathResource("static/images/hero-laptop-passport.png").getInputStream()) {
        Files.copy(image, storageRoot.resolve("draft-demo-" + name + ".png"));
      }
    }
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.addPrimarySources(List.of(EffectsFixture.class));
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    context =
        app.run(
            "--spring.profiles.active=test",
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.datasource.url=" + url,
            "--spring.datasource.driver-class-name="
                + (postgres ? "org.postgresql.Driver" : "org.h2.Driver"),
            "--spring.datasource.username="
                + (postgres ? System.getenv("SALE_DRAFT_TEST_DB_USER") : "sa"),
            "--spring.datasource.password="
                + (postgres ? System.getenv("SALE_DRAFT_TEST_DB_PASSWORD") : ""),
            "--spring.flyway.enabled=true",
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--app.jwt.secret=" + SECRET,
            "--app.storage.local-root=" + storageRoot);
    jdbc = context.getBean(JdbcTemplate.class);
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES (1,'r07-owner1@fixture.invalid','not-a-login-hash','owner1','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'r07-owner2@fixture.invalid','not-a-login-hash','owner2','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    jdbc.execute(
        "CREATE TABLE r07_trade_probe(id BIGINT PRIMARY KEY,listing_id BIGINT,request_state VARCHAR(20),trade_state VARCHAR(20))");
    jdbc.execute(
        "CREATE TABLE r07_transfer_probe(listing_id BIGINT PRIMARY KEY,state VARCHAR(20),token_valid BOOLEAN)");
    jdbc.execute(
        "CREATE TABLE r07_history_probe(product_id BIGINT PRIMARY KEY,visibility VARCHAR(20),cancel_calls INTEGER,history_calls INTEGER)");
    baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) {
      if (!"public".equals(schema)) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      context.close();
    }
    if (storageRoot != null) {
      if (!storageRoot
              .toAbsolutePath()
              .normalize()
              .getParent()
              .equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
          || !storageRoot.getFileName().toString().startsWith("listing-management-http-test-"))
        throw new IllegalStateException("Unexpected test cleanup directory");
      try (var paths = Files.walk(storageRoot)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  @BeforeEach
  void reset() {
    FAIL_TRADE.set(false);
    FAIL_HISTORY.set(false);
    jdbc.update("DELETE FROM listing_images");
    jdbc.update("DELETE FROM listings");
    jdbc.update("DELETE FROM audit_logs");
    jdbc.update("DELETE FROM draft_images");
    jdbc.update("DELETE FROM listing_drafts");
    jdbc.update("DELETE FROM r07_trade_probe");
    jdbc.update("DELETE FROM r07_transfer_probe");
    jdbc.update("DELETE FROM r07_history_probe");
    jdbc.update("UPDATE products SET owner_id=1,archived_at=NULL WHERE id=101");
    jdbc.update("UPDATE product_images SET deleted_at=NULL WHERE id IN (301,302,303)");
    seed(11, 101, 10011);
    jdbc.update("INSERT INTO listing_images VALUES (11,303,0,TRUE),(11,301,1,FALSE)");
    jdbc.update(
        "INSERT INTO listing_drafts(product_id,author_id,title) VALUES (102,1,'private-draft-keep')");
    jdbc.update(
        "INSERT INTO r07_trade_probe VALUES (1,11,'PENDING',NULL),(2,11,'PENDING',NULL),(3,99,'PENDING',NULL)");
    jdbc.update("INSERT INTO r07_transfer_probe VALUES (11,'NONE',FALSE)");
    jdbc.update("INSERT INTO r07_history_probe VALUES (101,'PUBLIC',0,0)");
  }

  @Test
  void managementIsOwnerOnlyAndDoesNotReturnPrivateProductOrStorageFields() throws Exception {
    var response = send("GET", "/api/v1/listings/11/management", "1", null);
    assertEquals(200, response.statusCode(), response.body());
    var item = data(response);
    assertTrue(item.get("canEdit").asBoolean());
    assertTrue(item.get("canCancel").asBoolean());
    assertEquals(List.of(303L, 301L), longs(item.get("selectedImageIds")));
    assertEquals(List.of(301L, 302L, 303L), longs(item.get("availableImages"), "imageId"));
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    for (String value :
        List.of("serial", "storageKey", "sellerId", "ownerId", "private-draft-keep"))
      assertFalse(response.body().contains(value));
    assertTrue(data(send("GET", "/api/v1/listings/11", "1", null)).get("canManage").asBoolean());
    assertFalse(data(send("GET", "/api/v1/listings/11", "2", null)).get("canManage").asBoolean());
    assertEquals(404, send("GET", "/api/v1/listings/11/management", "2", null).statusCode());
    assertEquals(401, send("GET", "/api/v1/listings/11/management", null, null).statusCode());
  }

  @Test
  void updatingConditionsAndPhotoOrderChangesPublicViewsButPreservesProductAndPublishIdentity()
      throws Exception {
    var before = jdbc.queryForMap("SELECT * FROM listings WHERE id=11");
    var product = jdbc.queryForMap("SELECT * FROM products WHERE id=101");
    var response = update(body(0, "revised", List.of(302L, 303L)));
    assertEquals(200, response.statusCode(), response.body());
    assertEquals(1, data(response).get("contentVersion").asLong());
    assertEquals(List.of(302L, 303L), longs(data(response).get("selectedImageIds")));
    var after = jdbc.queryForMap("SELECT * FROM listings WHERE id=11");
    for (String key :
        List.of(
            "product_id",
            "seller_id",
            "published_at",
            "source_draft_id",
            "source_draft_version",
            "status",
            "active_slot")) assertEquals(before.get(key), after.get(key), key);
    assertEquals(product, jdbc.queryForMap("SELECT * FROM products WHERE id=101"));
    var detail = data(send("GET", "/api/v1/listings/11", "2", null));
    assertEquals("revised", detail.get("content").get("title").asText());
    assertEquals(List.of(302L, 303L), longs(detail.get("images"), "imageId"));
    var summary =
        data(send("GET", "/api/v1/listings?minPrice=123456&maxPrice=123456", null, null))
            .get("items")
            .get(0);
    assertEquals("revised", summary.get("title").asText());
    assertEquals(
        302,
        jdbc.queryForObject(
            "SELECT product_image_id FROM listing_images WHERE listing_id=11 AND is_primary=TRUE",
            Long.class));
    assertEquals(1, auditCount("LISTING_UPDATED"));
    assertEquals(
        0, jdbc.queryForObject("SELECT cancel_calls FROM r07_history_probe", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM listing_drafts", Integer.class));
  }

  @Test
  void rejectsMissingInvalidOrForeignPhotosAndIncompletePublishedConditions() throws Exception {
    var valid = body(0, "revised", List.of(301L));
    for (var change :
        List.<Map<String, Object>>of(
            Map.of("version", -1),
            Map.of("title", " "),
            Map.of("description", " "),
            Map.of("title", "a".repeat(61)),
            Map.of("description", "a".repeat(2001)),
            Map.of("price", 0),
            Map.of("price", 1.5),
            Map.of("price", 1000000000000L),
            Map.of("conditionGrade", 6),
            Map.of("selectedImageIds", List.of()),
            Map.of("selectedImageIds", List.of(301L, 301L)),
            Map.of("selectedImageIds", List.of(401L)),
            Map.of("selectedImageIds", List.of(399L)),
            Map.of("selectedImageIds", List.of(999L)))) {
      var fields = new HashMap<>(valid);
      fields.putAll(change);
      assertEquals(400, update(fields).statusCode(), change.toString());
    }
    for (String required :
        List.of("version", "title", "description", "price", "conditionGrade", "selectedImageIds")) {
      var fields = new HashMap<>(valid);
      fields.remove(required);
      assertEquals(400, update(fields).statusCode(), required);
    }
    assertEquals(0, version());
    assertEquals(0, auditCount("LISTING_UPDATED"));
  }

  @Test
  void selectedFilesMustStillExistWhenUpdating() throws Exception {
    var source = storageRoot.resolve("draft-demo-side.png");
    byte[] bytes = Files.readAllBytes(source);
    Files.delete(source);
    try {
      assertEquals(404, update(body(0, "revised", List.of(302L))).statusCode());
    } finally {
      Files.write(source, bytes);
    }
    assertEquals(0, version());
  }

  @Test
  void requiresOriginalSellerCurrentOwnershipAndUnarchivedProductForEveryAction() throws Exception {
    assertEquals(
        404,
        send("PUT", "/api/v1/listings/11", "2", body(0, "foreign", List.of(301L))).statusCode());
    assertEquals(
        404,
        send("POST", "/api/v1/listings/11/cancel", "2", Map.of("version", 0, "reason", "foreign"))
            .statusCode());
    jdbc.update("UPDATE products SET owner_id=2 WHERE id=101");
    for (String user : List.of("1", "2")) {
      assertEquals(404, send("GET", "/api/v1/listings/11/management", user, null).statusCode());
      assertEquals(
          404,
          send("PUT", "/api/v1/listings/11", user, body(0, "foreign", List.of(301L))).statusCode());
      assertEquals(
          404,
          send(
                  "POST",
                  "/api/v1/listings/11/cancel",
                  user,
                  Map.of("version", 0, "reason", "foreign"))
              .statusCode());
    }
    jdbc.update("UPDATE products SET owner_id=1,archived_at=CURRENT_TIMESTAMP WHERE id=101");
    assertEquals(404, update(body(0, "archived", List.of(301L))).statusCode());
    assertEquals(404, cancel(0).statusCode());
    assertEquals(0, version());
    assertEquals("ACTIVE", status());
  }

  @Test
  void reservedAndEndedListingsCannotBeEditedOrReopened() throws Exception {
    for (String status : List.of("RESERVED", "SOLD", "CANCELLED")) {
      jdbc.update(
          "UPDATE listings SET status=?,active_slot=? WHERE id=11",
          status,
          status.equals("RESERVED") ? 1 : null);
      assertEquals(409, update(body(0, "revised", List.of(301L))).statusCode(), status);
      assertFalse(
          data(send("GET", "/api/v1/listings/11/management", "1", null))
              .get("canEdit")
              .asBoolean());
    }
    jdbc.update("UPDATE listings SET status='SOLD',active_slot=NULL WHERE id=11");
    assertEquals(409, cancel(0).statusCode());
    assertEquals(0, version());
  }

  @Test
  void staleVersionCannotOverwriteEditsOrCancelAChangedListing() throws Exception {
    assertEquals(200, update(body(0, "first", List.of(301L))).statusCode());
    assertEquals(409, update(body(0, "stale", List.of(303L))).statusCode());
    assertEquals(409, cancel(0).statusCode());
    assertEquals(
        "first", jdbc.queryForObject("SELECT title FROM listings WHERE id=11", String.class));
    assertEquals("ACTIVE", status());
    assertEquals(1, version());
  }

  @Test
  void cancellationPreservesRecordsAndDraftsButClosesPublicAccessAndHidesHistory()
      throws Exception {
    var response = cancel(0);
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("CANCELLED", data(response).get("status").asText());
    assertEquals(0, version());
    assertNull(jdbc.queryForObject("SELECT active_slot FROM listings WHERE id=11", Short.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM listings", Integer.class));
    assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM listing_images", Integer.class));
    assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM listing_drafts", Integer.class));
    assertEquals(1L, jdbc.queryForObject("SELECT owner_id FROM products WHERE id=101", Long.class));
    assertEquals(
        "PRIVATE", jdbc.queryForObject("SELECT visibility FROM r07_history_probe", String.class));
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM r07_trade_probe WHERE listing_id=11 AND request_state='CANCELLED'",
            Integer.class));
    assertEquals(
        "PENDING",
        jdbc.queryForObject(
            "SELECT request_state FROM r07_trade_probe WHERE listing_id=99", String.class));
    assertTrue(data(send("GET", "/api/v1/listings", null, null)).get("items").isEmpty());
    for (String path :
        List.of(
            "/api/v1/listings/11",
            "/api/v1/listings/11/images/303",
            "/api/v1/listings/11/thumbnail"))
      assertEquals(404, send("GET", path, "2", null).statusCode());
    assertEquals(1, auditCount("LISTING_CANCELLED"));
    assertEquals(
        "test cancellation",
        jdbc.queryForObject(
            "SELECT reason FROM audit_logs WHERE action='LISTING_CANCELLED'", String.class));
  }

  @Test
  void reservedCancellationIncludesAcceptedTradeAndPendingTransferToken() throws Exception {
    jdbc.update("UPDATE listings SET status='RESERVED' WHERE id=11");
    jdbc.update(
        "UPDATE r07_trade_probe SET request_state='ACCEPTED',trade_state='RESERVED' WHERE id=1");
    jdbc.update(
        "UPDATE r07_transfer_probe SET state='PENDING',token_valid=TRUE WHERE listing_id=11");
    assertEquals(200, cancel(0).statusCode());
    assertEquals("CANCELLED", status());
    assertEquals(
        "CANCELLED",
        jdbc.queryForObject("SELECT trade_state FROM r07_trade_probe WHERE id=1", String.class));
    assertEquals(
        "CANCELLED",
        jdbc.queryForObject(
            "SELECT state FROM r07_transfer_probe WHERE listing_id=11", String.class));
    assertFalse(
        jdbc.queryForObject(
            "SELECT token_valid FROM r07_transfer_probe WHERE listing_id=11", Boolean.class));
    assertEquals(
        "PRIVATE", jdbc.queryForObject("SELECT visibility FROM r07_history_probe", String.class));
  }

  @Test
  void tradeOrHistoryFailuresRollBackEveryCancellationEffect() throws Exception {
    for (var failing : List.of(FAIL_TRADE, FAIL_HISTORY)) {
      failing.set(true);
      assertEquals(503, cancel(0).statusCode());
      assertEquals("ACTIVE", status());
      assertEquals(
          1, jdbc.queryForObject("SELECT active_slot FROM listings WHERE id=11", Integer.class));
      assertEquals(
          2,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM r07_trade_probe WHERE listing_id=11 AND request_state='PENDING'",
              Integer.class));
      assertEquals(
          "PUBLIC", jdbc.queryForObject("SELECT visibility FROM r07_history_probe", String.class));
      assertEquals(
          0, jdbc.queryForObject("SELECT cancel_calls FROM r07_history_probe", Integer.class));
      assertEquals(0, auditCount("LISTING_CANCELLED"));
      failing.set(false);
    }
  }

  @Test
  void auditFailureRollsBackEditedPhotosAndAllCancellationEffects() throws Exception {
    jdbc.execute(
        "ALTER TABLE audit_logs ADD CONSTRAINT r07_audit_failure CHECK (action NOT IN ('LISTING_UPDATED','LISTING_CANCELLED'))");
    try {
      assertEquals(500, update(body(0, "rollback", List.of(301L, 302L))).statusCode());
      assertEquals(0, version());
      assertEquals(
          List.of(303L, 301L),
          jdbc.queryForList(
              "SELECT product_image_id FROM listing_images WHERE listing_id=11 ORDER BY sort_order",
              Long.class));
      assertEquals(500, cancel(0).statusCode());
      assertEquals("ACTIVE", status());
      assertEquals(
          "PUBLIC", jdbc.queryForObject("SELECT visibility FROM r07_history_probe", String.class));
      assertEquals(
          0, jdbc.queryForObject("SELECT cancel_calls FROM r07_history_probe", Integer.class));
    } finally {
      jdbc.execute("ALTER TABLE audit_logs DROP CONSTRAINT r07_audit_failure");
    }
  }

  @Test
  void cancellationRetryIsIdempotentAndCannotHideAResaleListingsHistory() throws Exception {
    assertEquals(200, cancel(0).statusCode());
    seed(12, 101, 10012);
    jdbc.update("UPDATE r07_history_probe SET visibility='PUBLIC'");
    assertEquals(200, cancel(0).statusCode());
    assertEquals(1, auditCount("LISTING_CANCELLED"));
    assertEquals(
        1, jdbc.queryForObject("SELECT cancel_calls FROM r07_history_probe", Integer.class));
    assertEquals(
        "PUBLIC", jdbc.queryForObject("SELECT visibility FROM r07_history_probe", String.class));
    assertEquals(1, data(send("GET", "/api/v1/listings", null, null)).get("items").size());
  }

  @Test
  void concurrentEditsUseVersionCheckInsideTheProductAndListingLocks() throws Exception {
    var first = CompletableFuture.supplyAsync(() -> uncheckedUpdate("first"));
    var second = CompletableFuture.supplyAsync(() -> uncheckedUpdate("second"));
    var results = new ArrayList<>(List.of(first.get(), second.get()));
    results.sort(Integer::compareTo);
    assertEquals(List.of(200, 409), results);
    assertEquals(1, version());
    assertEquals(1, auditCount("LISTING_UPDATED"));
  }

  @Test
  void simultaneousEditAndCancellationNeverBothCommitFromTheSameVersion() throws Exception {
    var edit = CompletableFuture.supplyAsync(() -> uncheckedUpdate("racing"));
    var stop =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return cancel(0).statusCode();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    var results = new ArrayList<>(List.of(edit.get(), stop.get()));
    results.sort(Integer::compareTo);
    assertEquals(List.of(200, 409), results);
    assertEquals(1, auditCount("LISTING_UPDATED") + auditCount("LISTING_CANCELLED"));
    assertTrue(
        (status().equals("ACTIVE") && version() == 1)
            || (status().equals("CANCELLED") && version() == 0));
  }

  @Test
  void cookieWritesStillRequireCsrfAndCancellationRequiresVersionAndReason() throws Exception {
    var uri = URI.create(baseUrl + "/api/v1/listings/11");
    String cookie = "access_token=" + token("1") + "; XSRF-TOKEN=r07-csrf";
    var raw = JSON.writeValueAsString(body(0, "cookie", List.of(301L)));
    assertEquals(
        403,
        HTTP.send(
                HttpRequest.newBuilder(uri)
                    .header("Cookie", cookie)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(raw))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(
        200,
        HTTP.send(
                HttpRequest.newBuilder(uri)
                    .header("Cookie", cookie)
                    .header("X-XSRF-TOKEN", "r07-csrf")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(raw))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    for (var fields :
        List.<Map<String, Object>>of(
            Map.of(),
            Map.of("version", 1),
            Map.of("reason", "reason"),
            Map.of("version", 1, "reason", " "),
            Map.of("version", 1, "reason", "a".repeat(501))))
      assertEquals(400, send("POST", "/api/v1/listings/11/cancel", "1", fields).statusCode());
    assertEquals("ACTIVE", status());
  }

  private static void seed(long id, long productId, long draftId) {
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES (?,?,1,'original',1500000,4,'original description','ACTIVE',CURRENT_TIMESTAMP,?,0,1)",
        id,
        productId,
        draftId);
  }

  private static Map<String, Object> body(long version, String title, List<Long> images) {
    return Map.of(
        "version",
        version,
        "title",
        title,
        "description",
        "changed description",
        "price",
        123456,
        "conditionGrade",
        5,
        "selectedImageIds",
        images);
  }

  private static HttpResponse<String> update(Map<String, Object> body) throws Exception {
    return send("PUT", "/api/v1/listings/11", "1", body);
  }

  private static HttpResponse<String> cancel(long version) throws Exception {
    return send(
        "POST",
        "/api/v1/listings/11/cancel",
        "1",
        Map.of("version", version, "reason", "test cancellation"));
  }

  private static int uncheckedUpdate(String title) {
    try {
      return update(body(0, title, List.of(301L, 302L))).statusCode();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String status() {
    return jdbc.queryForObject("SELECT status FROM listings WHERE id=11", String.class);
  }

  private static long version() {
    return jdbc.queryForObject("SELECT content_version FROM listings WHERE id=11", Long.class);
  }

  private static int auditCount(String action) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action=?", Integer.class, action);
  }

  private static JsonNode data(HttpResponse<String> response) {
    return JSON.readTree(response.body()).get("data");
  }

  private static List<Long> longs(JsonNode node) {
    var values = new ArrayList<Long>();
    node.forEach(value -> values.add(value.asLong()));
    return values;
  }

  private static List<Long> longs(JsonNode node, String key) {
    var values = new ArrayList<Long>();
    node.forEach(value -> values.add(value.get(key).asLong()));
    return values;
  }

  private static HttpResponse<String> send(String method, String path, String user, Object body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15));
    if (user != null) request.header("Authorization", "Bearer " + token(user));
    if (body != null) request.header("Content-Type", "application/json");
    return HTTP.send(
        request
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String user) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    long now = Instant.now().getEpochSecond();
    String header =
        encoder.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        encoder.encodeToString(
            ("{\"sub\":\"" + user + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}")
                .getBytes(StandardCharsets.UTF_8));
    String content = header + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return content
        + "."
        + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
  }
}
