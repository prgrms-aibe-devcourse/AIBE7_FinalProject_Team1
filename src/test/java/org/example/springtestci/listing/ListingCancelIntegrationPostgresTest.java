package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.ownership.service.OwnershipTransferService;
import org.example.springtestci.trade.service.InternalTransferCancellationPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 정식 이력·거래·소유권 테이블과 실제 Spring/JPA/JDBC/HTTP를 연결한다. 외부 AI와 시험 취소 포트는 사용하지 않는다. */
@EnabledIfEnvironmentVariable(named = "SALE_DRAFT_TEST_DB_URL", matches = ".*sale_ai_test.*")
class ListingCancelIntegrationPostgresTest {
  private static final String SECRET = "listing-cancel-only-test-signing-secret-at-least-32-bytes";
  private static final String RAW_TOKEN = "synthetic-internal-transfer-for-cancel-test";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static Path files;
  private static String base, schema;

  /** pending P2/P3 DDL은 정식 스키마 뒤에 격리 DB에만 적용하고 기능 플래그를 켠 일반 앱을 실행한다. */
  @BeforeAll
  static void start() throws Exception {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    if (url == null || !url.contains("sale_ai_test"))
      throw new IllegalStateException("Dedicated test database required");
    schema = "listing_cancel_" + Long.toUnsignedString(System.nanoTime());
    url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    var source =
        new DriverManagerDataSource(
            url,
            System.getenv("SALE_DRAFT_TEST_DB_USER"),
            System.getenv("SALE_DRAFT_TEST_DB_PASSWORD"));
    for (String[] locations :
        List.of(
            new String[] {"classpath:db/migration"},
            new String[] {"classpath:db/migration", "classpath:db/pending/trade-p2"},
            new String[] {
              "classpath:db/migration",
              "classpath:db/pending/trade-p2",
              "classpath:db/pending/trade-p3"
            })) {
      Flyway.configure()
          .dataSource(source)
          .schemas(schema)
          .defaultSchema(schema)
          .locations(locations)
          .outOfOrder(true)
          .load()
          .migrate();
    }
    files = Files.createTempDirectory("listing-cancel-http-");
    try (var image =
        new ClassPathResource("static/images/hero-laptop-passport.png").getInputStream()) {
      Files.copy(image, files.resolve("draft-demo-front.png"));
    }
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    context =
        app.run(
            "--spring.profiles.active=local",
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.datasource.url=" + url,
            "--spring.datasource.driver-class-name=org.postgresql.Driver",
            "--spring.datasource.username=" + System.getenv("SALE_DRAFT_TEST_DB_USER"),
            "--spring.datasource.password=" + System.getenv("SALE_DRAFT_TEST_DB_PASSWORD"),
            "--spring.flyway.enabled=true",
            "--spring.flyway.out-of-order=true",
            "--spring.flyway.locations=classpath:db/migration,classpath:db/pending/trade-p2,classpath:db/pending/trade-p3",
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--app.features.listing-history.enabled=true",
            "--app.features.trade-purchase-requests.enabled=true",
            "--app.features.trade-p3.enabled=true",
            "--app.features.trade-p4.enabled=false",
            "--app.ai.summary.enabled=false",
            "--app.ai.listing.enabled=false",
            "--app.ai.listing.api-key=",
            "--app.jwt.secret=" + SECRET,
            "--app.storage.local-root=" + files);
    jdbc = context.getBean(JdbcTemplate.class);
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'cancel-owner@fixture.invalid','unused','c-owner','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'cancel-buyer@fixture.invalid','unused','c-buyer','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(3,'cancel-other@fixture.invalid','unused','c-other','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) {
      jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      context.close();
    }
    if (files != null) {
      var root = files.toAbsolutePath().normalize();
      if (!root.getParent()
              .equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
          || !root.getFileName().toString().startsWith("listing-cancel-http-"))
        throw new IllegalStateException("Unexpected cleanup path");
      try (var paths = Files.walk(root)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  @BeforeEach
  void reset() {
    for (String table :
        List.of(
            "transaction_change_reviews",
            "ownership_transfers",
            "purchase_requests",
            "chat_read_positions",
            "chat_messages",
            "chat_rooms",
            "event_documents",
            "audit_logs",
            "product_events",
            "listing_images",
            "listings",
            "draft_images",
            "listing_drafts")) jdbc.update("DELETE FROM " + table);
    jdbc.update("UPDATE products SET owner_id=1,archived_at=NULL,facts_version=7 WHERE id=101");
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES(11,101,1,'cancel test',100000,4,'preserved listing','ACTIVE',CURRENT_TIMESTAMP,9001,0,1)");
    jdbc.update(
        "INSERT INTO listing_images(listing_id,sort_order,product_image_id,is_primary) VALUES(11,0,301,TRUE)");
    jdbc.update(
        "INSERT INTO chat_rooms(id,listing_id,seller_id,inquirer_id) VALUES(11,11,1,2),(12,11,1,3)");
    jdbc.update(
        "INSERT INTO chat_messages(id,room_id,sender_id,content) VALUES(11,11,2,'preserved conversation')");
    jdbc.update(
        "INSERT INTO purchase_requests(id,room_id,listing_id,request_status) VALUES(21,11,11,'PENDING'),(22,12,11,'PENDING')");
    jdbc.update(
        "INSERT INTO product_events(id,product_id,author_id,event_type,occurred_on,title,content,status) VALUES(101,101,1,'INSPECTION',CURRENT_DATE,'private title','private original history','VALID')");
  }

  @Test
  void myListingsAuthenticatesSellerAndKeepsReservedAndClosedHistory() throws Exception {
    jdbc.update("UPDATE listings SET status='RESERVED' WHERE id=11");
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES(12,101,2,'other seller',1000,4,'other','SOLD',CURRENT_TIMESTAMP,9012,0,NULL),(13,101,1,'my sold',2000,4,'sold','SOLD',CURRENT_TIMESTAMP,9013,0,NULL),(14,101,1,'my cancelled',3000,4,'cancelled','CANCELLED',CURRENT_TIMESTAMP,9014,0,NULL)");
    var response = send("GET", "/api/v1/my-listings?page=0&size=2&sellerId=2", "1", null);
    assertEquals(200, response.statusCode(), response.body());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertTrue(
        response.headers().allValues("Vary").stream().anyMatch(value -> value.contains("Cookie")));
    assertEquals(3, data(response).get("page").get("totalElements").asInt());
    assertEquals(2, data(response).get("items").size());
    var reserved = send("GET", "/api/v1/my-listings?status=RESERVED", "1", null);
    assertEquals(1, data(reserved).get("items").size());
    assertTrue(data(reserved).get("items").get(0).get("canManage").asBoolean());
    assertEquals(
        "/api/v1/products/101/images/301",
        data(reserved).get("items").get(0).get("thumbnailUrl").asText());
    assertEquals(
        12,
        data(send("GET", "/api/v1/my-listings", "2", null))
            .get("items")
            .get(0)
            .get("listingId")
            .asInt());
    assertEquals(0, data(send("GET", "/api/v1/my-listings", "3", null)).get("items").size());
    assertEquals(
        401,
        HTTP.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/my-listings")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    for (String query : List.of("page=-1", "size=0", "size=101", "status=UNKNOWN"))
      assertEquals(400, send("GET", "/api/v1/my-listings?" + query, "1", null).statusCode());
  }

  @Test
  void formerSellerCanSeeOnlyTheirListingSnapshotAfterProductTransfer() throws Exception {
    jdbc.update("UPDATE products SET owner_id=2 WHERE id=101");
    var response = send("GET", "/api/v1/my-listings", "1", null);
    assertEquals(200, response.statusCode(), response.body());
    var item = data(response).get("items").get(0);
    assertEquals("cancel test", item.get("title").asText());
    assertFalse(item.get("canManage").asBoolean());
    assertTrue(item.get("thumbnailUrl").isNull());
    assertEquals(404, send("GET", "/api/v1/listings/11/management", "1", null).statusCode());
  }

  @Test
  void activeCancellationUsesRealPendingRequestsAndPreservesHistoryImagesAndMessages()
      throws Exception {
    var history = jdbc.queryForList("SELECT * FROM product_events");
    var result = cancel("1", "판매를 잠시 중지합니다", null, 0);
    assertEquals(200, result.statusCode(), result.body());
    assertEquals("CANCELLED", value("SELECT status FROM listings WHERE id=11"));
    assertNull(jdbc.queryForObject("SELECT active_slot FROM listings WHERE id=11", Integer.class));
    assertEquals(
        2,
        count("purchase_requests WHERE request_status='CANCELLED' AND transaction_status IS NULL"));
    assertEquals(history, jdbc.queryForList("SELECT * FROM product_events"));
    assertEquals(1, count("listing_images"));
    assertEquals(1, count("chat_messages"));
    assertPublicClosedAndOwnerHistoryPreserved();
  }

  /** 실제 P2 수락 API의 기본 이전 writer까지 거쳐 예약을 만든 뒤 R07 취소를 검증한다. */
  @Test
  void acceptingARealPurchaseRequestCanBeFollowedByListingCancellation() throws Exception {
    var accepted = send("POST", "/api/v1/purchase-requests/21/accept", "1", null);
    assertEquals(201, accepted.statusCode(), accepted.body());
    assertEquals("RESERVED", value("SELECT status FROM listings WHERE id=11"));
    assertEquals(
        1, count("ownership_transfers WHERE transfer_type='INTERNAL' AND status='PENDING'"));
    var cancelled = cancel("1", "실제 수락 후 판매중지", "MUTUAL_AGREEMENT", 0);
    assertEquals(200, cancelled.statusCode(), cancelled.body());
    assertEquals(
        "CANCELLED", value("SELECT status FROM ownership_transfers WHERE purchase_request_id=21"));
    assertEquals(
        "CANCELLED", value("SELECT transaction_status FROM purchase_requests WHERE id=21"));
  }

  @Test
  void reservedCancellationEndsTheExactTransferAndInvalidatesItsLink() throws Exception {
    reserve();
    var result = cancel("1", "구매자와 취소하기로 합의했습니다", "MUTUAL_AGREEMENT", 0);
    assertEquals(200, result.statusCode(), result.body());
    assertEquals("CANCELLED", value("SELECT status FROM listings WHERE id=11"));
    assertEquals(
        "CANCELLED", value("SELECT transaction_status FROM purchase_requests WHERE id=21"));
    assertEquals("CANCELLED", value("SELECT request_status FROM purchase_requests WHERE id=22"));
    assertEquals(
        "MUTUAL_AGREEMENT", value("SELECT cancel_reason FROM purchase_requests WHERE id=21"));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT cancelled_by_id FROM purchase_requests WHERE id=21", Long.class));
    assertEquals("CANCELLED", value("SELECT status FROM ownership_transfers WHERE id=31"));
    assertNull(
        jdbc.queryForObject(
            "SELECT pending_slot FROM ownership_transfers WHERE id=31", Integer.class));
    assertNotNull(
        jdbc.queryForObject(
            "SELECT cancelled_at FROM ownership_transfers WHERE id=31",
            java.time.OffsetDateTime.class));
    assertEquals(
        "구매자와 취소하기로 합의했습니다",
        value("SELECT reason FROM audit_logs WHERE action='LISTING_CANCELLED'"));
    var verified = send("GET", "/api/v1/ownership/transfers/verify?token=" + RAW_TOKEN, "2", null);
    assertEquals(404, verified.statusCode(), verified.body());
    assertEquals(
        409,
        send("POST", "/api/v1/ownership/transfers/accept", "2", Map.of("token", RAW_TOKEN))
            .statusCode());
    assertPublicClosedAndOwnerHistoryPreserved();
  }

  @Test
  void identicalRetryPreservesFirstCancellationTimeReasonAndAudit() throws Exception {
    reserve();
    assertEquals(200, cancel("1", "first", "MUTUAL_AGREEMENT", 0).statusCode());
    var after = snapshot();
    assertEquals(200, cancel("1", "different retry", "SELF_CIRCUMSTANCES", 0).statusCode());
    assertEquals(after, snapshot());
  }

  @Test
  void oldCancellationRetryCannotHideARepublishedListing() throws Exception {
    reserve();
    assertEquals(200, cancel("1", "first", "MUTUAL_AGREEMENT", 0).statusCode());
    var draft =
        send(
            "POST",
            "/api/v1/products/101/listing-draft",
            "1",
            Map.of(
                "title",
                "resale",
                "description",
                "new listing",
                "price",
                100000,
                "conditionGrade",
                4,
                "selectedImageIds",
                List.of(301)));
    assertEquals(201, draft.statusCode(), draft.body());
    var published =
        send(
            "POST",
            "/api/v1/products/101/listings",
            "1",
            Map.of(
                "draftId",
                data(draft).get("draftId").asLong(),
                "version",
                data(draft).get("version").asLong()));
    assertEquals(201, published.statusCode(), published.body());
    long newId = data(published).get("listingId").asLong();
    var before = snapshot();
    assertEquals(200, cancel("1", "retry", "SELF_CIRCUMSTANCES", 0).statusCode());
    assertEquals(before, snapshot());
    assertEquals(200, send("GET", "/api/v1/listings/" + newId, "2", null).statusCode());
  }

  @Test
  void releasedPendingSlotCanBeUsedByANewDirectTransferAndOldRetryDoesNotCancelIt()
      throws Exception {
    reserve();
    assertEquals(200, cancel("1", "first", "MUTUAL_AGREEMENT", 0).statusCode());
    var issued =
        send("POST", "/api/v1/ownership/transfers", "1", Map.of("productId", 101, "toUserId", 3));
    assertEquals(201, issued.statusCode(), issued.body());
    var before = snapshot();
    assertEquals(200, cancel("1", "retry", "SELF_CIRCUMSTANCES", 0).statusCode());
    assertEquals(before, snapshot());
    assertEquals(1, count("ownership_transfers WHERE status='PENDING' AND transfer_type='DIRECT'"));
  }

  @Test
  void unmatchedOrEndedTransferDoesNotAllowPartialListingOrTradeCancellation() throws Exception {
    for (String update :
        List.of(
            "to_user_id=3",
            "purchase_request_id=22",
            "transfer_type='DIRECT'",
            "status='COMPLETED',pending_slot=NULL")) {
      reset();
      reserve();
      jdbc.update("UPDATE ownership_transfers SET " + update + " WHERE id=31");
      var before = snapshot();
      var result = cancel("1", "stop", "MUTUAL_AGREEMENT", 0);
      assertEquals(409, result.statusCode(), result.body());
      assertEquals(before, snapshot());
    }
  }

  @Test
  void missingTransferCannotBeTreatedAsASuccessfulCancellation() throws Exception {
    reserve();
    jdbc.update("DELETE FROM ownership_transfers");
    var before = snapshot();
    assertEquals(409, cancel("1", "stop", "MUTUAL_AGREEMENT", 0).statusCode());
    assertEquals(before, snapshot());
  }

  @Test
  void ownershipWriteFailureRollsBackTheAlreadyFlushedListing() throws Exception {
    reserve();
    assertRollbackWithConstraint("ownership_transfers", "status <> 'CANCELLED'", 503);
  }

  @Test
  void failureWhileCancellingOtherPendingRequestsRollsBackReservedTradeAndTransfer()
      throws Exception {
    reserve();
    assertRollbackWithConstraint(
        "purchase_requests", "id <> 22 OR request_status <> 'CANCELLED'", 500);
  }

  @Test
  void lastAuditFailureRollsBackListingTradeTransferAndVisibilityAudit() throws Exception {
    reserve();
    assertRollbackWithConstraint("audit_logs", "action <> 'LISTING_CANCELLED'", 500);
  }

  @Test
  void wrongActorStaleVersionAndInvalidStructuredReasonLeaveEverythingUnchanged() throws Exception {
    reserve();
    var before = snapshot();
    assertEquals(404, cancel("2", "not seller", "MUTUAL_AGREEMENT", 0).statusCode());
    assertEquals(409, cancel("1", "stale", "MUTUAL_AGREEMENT", 1).statusCode());
    assertEquals(400, cancel("1", "unmapped free text", null, 0).statusCode());
    assertEquals(400, cancel("1", "stop", "UNKNOWN", 0).statusCode());
    assertEquals(before, snapshot());
  }

  @Test
  void legacyEnumReasonAndNormalReservationCancellationStillWork() throws Exception {
    reserve();
    assertEquals(200, cancel("1", "MUTUAL_AGREEMENT", null, 0).statusCode());
    reset();
    reserve();
    var result =
        send("POST", "/api/v1/trades/21/cancel", "2", Map.of("reason", "MUTUAL_AGREEMENT"));
    assertEquals(200, result.statusCode(), result.body());
    assertEquals("ACTIVE", value("SELECT status FROM listings WHERE id=11"));
    assertEquals("PENDING", value("SELECT request_status FROM purchase_requests WHERE id=22"));
    assertEquals("CANCELLED", value("SELECT status FROM ownership_transfers WHERE id=31"));
    assertEquals(200, send("GET", "/api/v1/listings/11", "2", null).statusCode());
  }

  @Test
  void completedTradeIsNeverRewrittenAsAListingCancellation() throws Exception {
    reserve();
    jdbc.update("UPDATE purchase_requests SET transaction_status='COMPLETED' WHERE id=21");
    var before = snapshot();
    assertEquals(409, cancel("1", "stop", "MUTUAL_AGREEMENT", 0).statusCode());
    assertEquals(before, snapshot());
  }

  @Test
  void cancellationPortCannotRunOutsideTheCallingTransaction() {
    assertThrows(
        IllegalTransactionStateException.class,
        () ->
            context
                .getBean(InternalTransferCancellationPort.class)
                .cancelPending(new InternalTransferCancellationPort.Command(101, 21, 1, 2)));
  }

  /** 두 HTTP 요청을 실제 제품 잠금에서 대기시켜 한 번의 취소와 한 번의 멱등 응답을 검증한다. */
  @Test
  void concurrentRetriesCommitOneCancellationAndOneAuditSet() throws Exception {
    reserve();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try (var blocker = context.getBean(javax.sql.DataSource.class).getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement =
              blocker.prepareStatement("SELECT id FROM products WHERE id=101 FOR UPDATE");
          var rows = statement.executeQuery()) {
        assertTrue(rows.next());
      }
      var first =
          pool.submit(
              (Callable<HttpResponse<String>>)
                  () -> {
                    start.await();
                    return cancel("1", "first", "MUTUAL_AGREEMENT", 0);
                  });
      var second =
          pool.submit(
              (Callable<HttpResponse<String>>)
                  () -> {
                    start.await();
                    return cancel("1", "second", "SELF_CIRCUMSTANCES", 0);
                  });
      start.countDown();
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        int waiting;
        do {
          waiting =
              jdbc.queryForObject(
                  "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND lower(query) LIKE '%from products%for update%'",
                  Integer.class);
          if (waiting >= 2) break;
          Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertTrue(waiting >= 2, "Both requests must wait on the product lock");
      } finally {
        blocker.commit();
      }
      assertEquals(200, first.get(20, TimeUnit.SECONDS).statusCode());
      assertEquals(200, second.get(20, TimeUnit.SECONDS).statusCode());
      assertEquals(1, count("audit_logs WHERE action='TRADE_RESERVATION_CANCELLED'"));
      assertEquals(1, count("audit_logs WHERE action='LISTING_CANCELLED'"));
      assertEquals(1, count("audit_logs WHERE action='HISTORY_VISIBILITY_CHANGED'"));
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  private static void reserve() {
    jdbc.update("UPDATE listings SET status='RESERVED' WHERE id=11");
    jdbc.update(
        "UPDATE purchase_requests SET request_status='ACCEPTED',transaction_status='RESERVED' WHERE id=21");
    jdbc.update(
        "INSERT INTO ownership_transfers(id,product_id,purchase_request_id,from_user_id,to_user_id,transfer_type,status,pending_slot,token_hash,expires_at) VALUES(31,101,21,1,2,'INTERNAL','PENDING',1,?,?)",
        OwnershipTransferService.hashToken(RAW_TOKEN),
        Timestamp.from(Instant.now().plusSeconds(3600)));
  }

  private static void assertPublicClosedAndOwnerHistoryPreserved() throws Exception {
    for (String path :
        List.of(
            "/api/v1/listings/11",
            "/api/v1/listings/11/images/301",
            "/api/v1/listings/11/thumbnail",
            "/api/v1/listings/11/summary"))
      assertEquals(404, send("GET", path, "2", null).statusCode(), path);
    assertEquals(200, send("GET", "/api/v1/products/101/events", "1", null).statusCode());
    assertEquals(404, send("GET", "/api/v1/products/101/events", "2", null).statusCode());
  }

  private static void assertRollbackWithConstraint(String table, String check, int status)
      throws Exception {
    var before = snapshot();
    jdbc.execute(
        "ALTER TABLE " + table + " ADD CONSTRAINT cancel_test_failure CHECK (" + check + ")");
    try {
      var response = cancel("1", "stop", "MUTUAL_AGREEMENT", 0);
      assertEquals(status, response.statusCode(), response.body());
      assertEquals(before, snapshot());
    } finally {
      jdbc.execute("ALTER TABLE " + table + " DROP CONSTRAINT cancel_test_failure");
    }
  }

  private static Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String table :
        List.of(
            "listings",
            "purchase_requests",
            "ownership_transfers",
            "audit_logs",
            "product_events",
            "chat_messages"))
      result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    result.put(
        "images", jdbc.queryForList("SELECT * FROM listing_images ORDER BY listing_id,sort_order"));
    result.put(
        "owner", jdbc.queryForMap("SELECT owner_id,facts_version FROM products WHERE id=101"));
    return result;
  }

  private static String value(String sql) {
    return jdbc.queryForObject(sql, String.class);
  }

  private static int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private static JsonNode data(HttpResponse<String> response) {
    return JSON.readTree(response.body()).get("data");
  }

  private static HttpResponse<String> cancel(
      String user, String reason, String tradeReason, long version) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", version);
    body.put("reason", reason);
    if (tradeReason != null) body.put("tradeReason", tradeReason);
    return send("POST", "/api/v1/listings/11/cancel", user, body);
  }

  private static HttpResponse<String> send(String method, String path, String user, Object body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token(user));
    if (body != null) request.header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String user) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header =
        encoder.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        encoder.encodeToString(
            ("{\"sub\":\"" + user + "\",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}")
                .getBytes(StandardCharsets.UTF_8));
    String content = header + "." + payload;
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return content
        + "."
        + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
  }
}
