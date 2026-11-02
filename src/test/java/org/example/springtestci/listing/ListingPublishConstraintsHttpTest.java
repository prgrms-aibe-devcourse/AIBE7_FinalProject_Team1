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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.common.security.RequestRateLimiter;
import org.example.springtestci.listing.service.ListingPublishConstraints;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 일반 local 설정의 실제 게시 검사·소유권 서비스·HTTP를 검증한다. 외부 AI·거래 쓰기는 실행하지 않는다. */
class ListingPublishConstraintsHttpTest {
  private static final String SECRET = "publish-constraints-only-test-secret-at-least-32-bytes";
  private static final String DRAFT = "/api/v1/products/101/listing-draft";
  private static final String PUBLISH = "/api/v1/products/101/listings";
  private static final String TRANSFER = "/api/v1/ownership/transfers";
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static Path storage;
  private static String base, schema;
  private static boolean postgres;

  @TestConfiguration(proxyBeanMethods = false)
  static class RequestLimits {
    @Bean
    @Primary
    RequestRateLimiter limits() {
      return org.mockito.Mockito.mock(RequestRateLimiter.class);
    }
  }

  /** 업무 Bean은 기본 구현이며 Redis 요청 제한만 대역이다. PostgreSQL은 P2 DDL도 격리 스키마에 적용한다. */
  @BeforeAll
  static void start() throws Exception {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    postgres = url != null && !url.isBlank();
    if (postgres && !url.contains("sale_ai_test"))
      throw new IllegalStateException("Dedicated sale_ai_test database required");
    schema =
        postgres ? "publish_constraints_" + Long.toUnsignedString(System.nanoTime()) : "public";
    url =
        postgres
            ? url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema
            : "jdbc:h2:mem:publish_constraints_http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    storage = Files.createTempDirectory("listing-constraints-http-");
    try (var image =
        new ClassPathResource("static/images/hero-laptop-passport.png").getInputStream()) {
      Files.copy(image, storage.resolve("draft-demo-front.png"));
    }
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.addPrimarySources(List.of(RequestLimits.class));
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    context =
        app.run(
            "--spring.profiles.active=local",
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
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--app.features.listing-history.enabled=true",
            "--app.features.trade-purchase-requests.enabled=false",
            "--app.features.trade-p3.enabled=false",
            "--app.features.trade-p4.enabled=false",
            "--app.ai.summary.enabled=false",
            "--app.ai.listing.enabled=false",
            "--app.ai.listing.api-key=",
            "--app.jwt.secret=" + SECRET,
            "--app.storage.local-root=" + storage);
    jdbc = context.getBean(JdbcTemplate.class);
    if (postgres) {
      Flyway.configure()
          .dataSource(context.getBean(javax.sql.DataSource.class))
          .schemas(schema)
          .defaultSchema(schema)
          .locations("classpath:db/migration", "classpath:db/pending/trade-p2")
          .outOfOrder(true)
          .load()
          .migrate();
    } else {
      // 부분 인덱스를 지원하지 않는 H2에서는 읽기 계약에 필요한 시험 테이블만 사용한다.
      jdbc.execute(
          "CREATE TABLE purchase_requests(id BIGINT PRIMARY KEY,room_id BIGINT,listing_id BIGINT,request_status VARCHAR(20),transaction_status VARCHAR(20))");
    }
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'publish-owner@fixture.invalid','unused','pub-owner','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'publish-buyer@fixture.invalid','unused','pub-buyer','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) {
      if (postgres) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      context.close();
    }
    if (storage != null) {
      var root = storage.toAbsolutePath().normalize();
      if (!root.getParent()
              .equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
          || !root.getFileName().toString().startsWith("listing-constraints-http-"))
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
            "purchase_requests",
            "chat_rooms",
            "ownership_transfers",
            "audit_logs",
            "listing_images",
            "listings",
            "draft_images",
            "listing_drafts")) jdbc.update("DELETE FROM " + table);
    jdbc.update("UPDATE products SET owner_id=1,archived_at=NULL WHERE id=101");
  }

  @Test
  void ordinaryConfigurationInstallsATransactionBoundRealChecker() {
    assertThrows(
        IllegalTransactionStateException.class,
        () -> context.getBean(ListingPublishConstraints.class).inspect(101));
  }

  @Test
  void disabledTradeIntegrationCannotPretendToHaveCancelledAListing() throws Exception {
    var created = publish(saveDraft());
    long id = data(created).get("listingId").asLong();
    var response =
        send(
            "POST",
            "/api/v1/listings/" + id + "/cancel",
            "1",
            "{\"version\":0,\"reason\":\"stop\"}");
    assertEquals(503, response.statusCode(), response.body());
    assertEquals(
        "ACTIVE", jdbc.queryForObject("SELECT status FROM listings WHERE id=?", String.class, id));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action='LISTING_CANCELLED'", Integer.class));
  }

  @Test
  void publishingWithoutAnOngoingTradeOrTransferSucceedsAndRetainsIdempotency() throws Exception {
    var draft = saveDraft();
    var first = publish(draft);
    assertEquals(201, first.statusCode(), first.body());
    assertEquals(0, count("listing_drafts"));
    transfer(101, "PENDING", "DIRECT", Instant.now().plusSeconds(3600));
    var retry = publish(draft);
    assertEquals(200, retry.statusCode(), retry.body());
    assertEquals(data(first).get("listingId"), data(retry).get("listingId"));
    assertEquals(1, count("listings"));
  }

  @Test
  void aReservedTradeOnAnyOldListingBlocksANewPublicationAndKeepsTheDraft() throws Exception {
    trade("ACCEPTED", "RESERVED");
    blockedAndPreserved(saveDraft(), "SALE_003", 409);
  }

  @Test
  void pendingPurchasesAndEndedTransactionsDoNotBlockANewPublication() throws Exception {
    trade("PENDING", null);
    jdbc.update(
        "INSERT INTO purchase_requests(id,room_id,listing_id,request_status,transaction_status) VALUES(12,11,11,'ACCEPTED','COMPLETED'),(13,11,11,'CANCELLED','CANCELLED')");
    var result = publish(saveDraft());
    assertEquals(201, result.statusCode(), result.body());
  }

  @Test
  void validDirectAndInternalTransfersBothBlockPublishingWithoutChangingTheirRows()
      throws Exception {
    var draft = saveDraft();
    for (String type : List.of("DIRECT", "INTERNAL")) {
      jdbc.update("DELETE FROM ownership_transfers");
      transfer(101, "PENDING", type, Instant.now().plusSeconds(3600));
      var before = jdbc.queryForList("SELECT * FROM ownership_transfers");
      blockedAndPreserved(draft, "SALE_003", 409);
      assertEquals(before, jdbc.queryForList("SELECT * FROM ownership_transfers"));
    }
  }

  @Test
  void otherProductsTransfersAndExpiredPendingDoNotBlockOrGetRewritten() throws Exception {
    transfer(201, "PENDING", "DIRECT", Instant.now().plusSeconds(3600));
    transfer(101, "PENDING", "DIRECT", Instant.now().minusSeconds(3600));
    var before = jdbc.queryForList("SELECT * FROM ownership_transfers ORDER BY product_id");
    var result = publish(saveDraft());
    assertEquals(201, result.statusCode(), result.body());
    assertEquals(
        before, jdbc.queryForList("SELECT * FROM ownership_transfers ORDER BY product_id"));
  }

  @Test
  void eitherUndeployedSourceReturnsUnavailableAndKeepsTheWholeDraft() throws Exception {
    var draft = saveDraft();
    for (String table : List.of("purchase_requests", "ownership_transfers")) {
      jdbc.execute("ALTER TABLE " + table + " RENAME TO unavailable_publish_source");
      try {
        blockedAndPreserved(draft, "SALE_004", 503);
      } finally {
        jdbc.execute("ALTER TABLE unavailable_publish_source RENAME TO " + table);
      }
    }
  }

  @Test
  void activeAndReservedListingsPreventExternalTransferIssuance() throws Exception {
    var result = publish(saveDraft());
    assertEquals(201, result.statusCode(), result.body());
    for (String status : List.of("ACTIVE", "RESERVED")) {
      jdbc.update("UPDATE listings SET status=?", status);
      var response = createTransfer();
      assertEquals(409, response.statusCode(), response.body());
      assertEquals("OWN_005", JSON.readTree(response.body()).get("code").asText());
      assertEquals(0, count("ownership_transfers"));
    }
  }

  @Test
  void cancellingATransferUnblocksPublishingThroughTheRealOwnershipApi() throws Exception {
    var created = createTransfer();
    assertEquals(201, created.statusCode(), created.body());
    var draft = saveDraft();
    blockedAndPreserved(draft, "SALE_003", 409);
    var cancelled =
        send(
            "POST",
            TRANSFER + "/" + data(created).get("transferId").asLong() + "/cancel",
            "1",
            "{}");
    assertEquals(200, cancelled.statusCode(), cancelled.body());
    assertEquals(201, publish(draft).statusCode());
  }

  @Test
  void acceptingATransferRemovesTheOldDraftAndPreventsPublishingIt() throws Exception {
    var draft = saveDraft();
    var created = createTransfer();
    var accepted =
        send(
            "POST",
            TRANSFER + "/accept",
            "2",
            JSON.writeValueAsString(Map.of("token", data(created).get("rawToken").asText())));
    assertEquals(200, accepted.statusCode(), accepted.body());
    assertEquals(1L, jdbc.queryForObject("SELECT owner_id FROM products WHERE id=101", Long.class));
    var approved =
        send(
            "POST",
            TRANSFER + "/" + data(created).get("transferId").asLong() + "/approve",
            "1",
            JSON.writeValueAsString(
                Map.of(
                    "expectedRecipientId",
                    2L,
                    "expectedFactsVersion",
                    jdbc.queryForObject(
                        "SELECT facts_version FROM products WHERE id=101", Long.class))));
    assertEquals(200, approved.statusCode(), approved.body());
    assertEquals(404, publish(draft).statusCode());
    assertEquals(0, count("listings"));
    assertEquals(0, count("listing_drafts"));
    assertEquals(0, count("draft_images"));
  }

  /** 실제 PostgreSQL의 서로 다른 HTTP 트랜잭션에서 두 쓰기를 동시에 시작해 한쪽만 성공해야 한다. */
  @Test
  void concurrentPublicationAndExternalTransferCannotBothCommit() throws Exception {
    Assumptions.assumeTrue(postgres, "Real PostgreSQL concurrency check");
    var draft = saveDraft();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try (var blocker = context.getBean(javax.sql.DataSource.class).getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement =
          blocker.prepareStatement("SELECT id FROM products WHERE id=101 FOR UPDATE")) {
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
        }
      }
      var publication =
          pool.submit(
              (Callable<HttpResponse<String>>)
                  () -> {
                    start.await();
                    return publish(draft);
                  });
      var transfer =
          pool.submit(
              (Callable<HttpResponse<String>>)
                  () -> {
                    start.await();
                    return createTransfer();
                  });
      start.countDown();
      // 두 HTTP 요청이 실제 제품 잠금에서 대기한 뒤 함께 풀려야 단순 순차 실행으로 통과하지 않는다.
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
        assertTrue(waiting >= 2, "Both requests must wait on the shared product lock");
      } finally {
        blocker.commit();
      }
      var published = publication.get(20, TimeUnit.SECONDS);
      var issued = transfer.get(20, TimeUnit.SECONDS);
      assertTrue(
          (published.statusCode() == 201 && issued.statusCode() == 409)
              || (published.statusCode() == 409 && issued.statusCode() == 201),
          published.body() + " / " + issued.body());
      assertEquals(1, count("listings") + count("ownership_transfers"));
    } finally {
      start.countDown();
      pool.shutdownNow();
    }
  }

  private static JsonNode saveDraft() throws Exception {
    var response =
        send(
            "POST",
            DRAFT,
            "1",
            "{\"title\":\"게시 검사 테스트\",\"description\":\"정상 판매 설명\",\"price\":100000,\"conditionGrade\":4,\"selectedImageIds\":[301]}");
    assertEquals(201, response.statusCode(), response.body());
    return data(response);
  }

  private static HttpResponse<String> publish(JsonNode draft) throws Exception {
    return send(
        "POST",
        PUBLISH,
        "1",
        JSON.writeValueAsString(
            Map.of(
                "draftId",
                draft.get("draftId").asLong(),
                "version",
                draft.get("version").asLong())));
  }

  private static HttpResponse<String> createTransfer() throws Exception {
    return send("POST", TRANSFER, "1", "{\"productId\":101,\"toUserId\":2}");
  }

  /** 실패 전후 초안·사진 선택·감사 기록과 판매글 수가 같아야 한다. */
  private static void blockedAndPreserved(JsonNode draft, String code, int status)
      throws Exception {
    var before = data(send("GET", DRAFT, "1", null));
    int listings = count("listings"), photos = count("listing_images"), audit = count("audit_logs");
    var response = publish(draft);
    assertEquals(status, response.statusCode(), response.body());
    assertEquals(code, JSON.readTree(response.body()).get("code").asText());
    assertEquals(before, data(send("GET", DRAFT, "1", null)));
    assertEquals(listings, count("listings"));
    assertEquals(photos, count("listing_images"));
    assertEquals(audit, count("audit_logs"));
  }

  private static void transfer(long productId, String status, String type, Instant expiry) {
    jdbc.update(
        "INSERT INTO ownership_transfers(product_id,from_user_id,to_user_id,token_hash,status,transfer_type,expires_at,pending_slot) VALUES(?,1,2,?,?,?, ?,?)",
        productId,
        "synthetic-transfer-" + productId,
        status,
        type,
        Timestamp.from(expiry),
        "PENDING".equals(status) ? 1 : null);
  }

  private static void trade(String requestStatus, String transactionStatus) {
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES(11,101,1,'old listing',100000,4,'old','CANCELLED',CURRENT_TIMESTAMP,9001,0,NULL)");
    jdbc.update("INSERT INTO chat_rooms(id,listing_id,seller_id,inquirer_id) VALUES(11,11,1,2)");
    jdbc.update(
        "INSERT INTO purchase_requests(id,room_id,listing_id,request_status,transaction_status) VALUES(11,11,11,?,?)",
        requestStatus,
        transactionStatus);
  }

  private static int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }

  private static JsonNode data(HttpResponse<String> response) {
    return JSON.readTree(response.body()).get("data");
  }

  private static HttpResponse<String> send(String method, String path, String user, String body)
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
            : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String subject) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header =
        encoder.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        encoder.encodeToString(
            ("{\"sub\":\""
                    + subject
                    + "\",\"exp\":"
                    + (Instant.now().getEpochSecond() + 3600)
                    + "}")
                .getBytes(StandardCharsets.UTF_8));
    String content = header + "." + payload;
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return content
        + "."
        + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
  }
}
