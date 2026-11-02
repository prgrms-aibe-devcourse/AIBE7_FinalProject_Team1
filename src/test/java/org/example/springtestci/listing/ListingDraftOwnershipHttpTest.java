package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.common.security.RequestRateLimiter;
import org.example.springtestci.ownership.event.OwnershipTransferred;
import org.example.springtestci.product.repository.ProductRepository;
import org.example.springtestci.trade.repository.TradeAuditRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP·JWT·CSRF·Flyway·JPA/JDBC로 원자성·경합을 검사한다. Redis 요청 제한만 시험 대역이다. */
class ListingDraftOwnershipHttpTest {
  private static final String SECRET = "ownership-draft-test-only-key-at-least-32-bytes";
  private static final String DRAFT = "/api/v1/products/101/listing-draft";
  private static final String TRANSFERS = "/api/v1/ownership/transfers";
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicBoolean FAIL_AUDIT = new AtomicBoolean();
  private static final AtomicReference<Runnable> BEFORE_AUDIT = new AtomicReference<>();
  private static final AtomicReference<CountDownLatch> AT_PRODUCT_LOCK = new AtomicReference<>();
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static String base, schema;
  private long oldDraftId, oldVersion;

  @TestConfiguration(proxyBeanMethods = false)
  static class Faults {
    @Bean
    @Primary
    RequestRateLimiter requestLimits() {
      return org.mockito.Mockito.mock(RequestRateLimiter.class);
    }

    @Bean
    @Primary
    ProductRepository observedProducts(JdbcTemplate jdbc) {
      return new ProductRepository(jdbc) {
        @Override
        public boolean lockOwned(long productId, long ownerId) {
          if (productId == 101 && AT_PRODUCT_LOCK.get() != null) AT_PRODUCT_LOCK.get().countDown();
          return super.lockOwned(productId, ownerId);
        }
      };
    }

    @Bean
    @Primary
    TradeAuditRepository audit(JdbcTemplate jdbc) {
      return new TradeAuditRepository(jdbc) {
        @Override
        public void log(
            long productId,
            Long actorId,
            long targetId,
            String action,
            String before,
            String after,
            String reason) {
          if ("OWNERSHIP_TRANSFERRED".equals(action) && BEFORE_AUDIT.get() != null)
            BEFORE_AUDIT.get().run();
          super.log(productId, actorId, targetId, action, before, after, reason);
          if ("OWNERSHIP_TRANSFERRED".equals(action) && FAIL_AUDIT.get())
            throw new IllegalStateException("Injected completion audit failure");
        }
      };
    }
  }

  @BeforeAll
  static void start() {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    boolean postgres = url != null && !url.isBlank();
    if (postgres && !url.contains("sale_ai_test"))
      throw new IllegalStateException("Dedicated sale_ai_test DB required");
    schema = postgres ? "draft_ownership_" + Long.toUnsignedString(System.nanoTime()) : "public";
    if (postgres) url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    else
      url =
          "jdbc:h2:mem:draft_ownership;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.addPrimarySources(List.of(Faults.class));
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
            "--app.ai.summary.enabled=false",
            "--app.ai.listing.api-key=",
            "--app.jwt.secret=" + SECRET);
    jdbc = context.getBean(JdbcTemplate.class);
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'owner@draft.invalid','unused','owner','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'buyer@draft.invalid','unused','buyer','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(3,'other@draft.invalid','unused','other','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    // 시험 전용 FK로 삭제 저장 실패를 주입한다. 운영 마이그레이션은 변경하지 않는다.
    jdbc.execute(
        "CREATE TABLE draft_cleanup_guard(draft_id BIGINT PRIMARY KEY REFERENCES listing_drafts(id))");
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() {
    if (context != null) {
      try {
        if (!"public".equals(schema)) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      } finally {
        context.close();
      }
    }
  }

  @BeforeEach
  void fixtures() throws Exception {
    FAIL_AUDIT.set(false);
    BEFORE_AUDIT.set(null);
    AT_PRODUCT_LOCK.set(null);
    jdbc.update("DELETE FROM draft_cleanup_guard");
    jdbc.update("DELETE FROM audit_logs");
    jdbc.update("DELETE FROM ownership_transfers");
    jdbc.update("DELETE FROM draft_images");
    jdbc.update("DELETE FROM listing_drafts");
    jdbc.update("UPDATE products SET owner_id=1 WHERE id=101");
    var old =
        send(
            "POST",
            DRAFT,
            1,
            "{\"title\":\"previous private draft\",\"selectedImageIds\":[301,302]}");
    assertEquals(201, old.statusCode(), old.body());
    oldDraftId = data(old).path("draftId").asLong();
    oldVersion = data(old).path("version").asLong();
    assertTrue(oldDraftId > 0);
    assertEquals(
        201,
        send("POST", "/api/v1/products/102/listing-draft", 1, "{\"selectedImageIds\":[304]}")
            .statusCode());
  }

  @Test
  void acceptanceDeletesOnlyTheTransferredProductsDraftAndAllowsFreshWriting() throws Exception {
    var transfer = create();
    assertEquals(200, accept(transfer, 2).statusCode());
    assertEquals(0, count("listing_drafts", "product_id=101"));
    assertEquals(0, count("draft_images", "draft_id=" + oldDraftId));
    assertEquals(4, count("product_images", "product_id=101"));
    assertEquals(1, count("listing_drafts", "product_id=102"));
    assertEquals(1, count("draft_images", "product_image_id=304"));
    assertEquals(2L, owner());
    assertEquals("COMPLETED", transferStatus());
    assertEquals(404, send("GET", DRAFT, 1, null).statusCode());
    assertEquals(404, send("GET", DRAFT, 2, null).statusCode());

    var fresh =
        send("POST", DRAFT, 2, "{\"title\":\"new owners draft\",\"selectedImageIds\":[301]}");
    assertEquals(201, fresh.statusCode(), fresh.body());
    assertNotEquals(oldDraftId, data(fresh).path("draftId").asLong());
    assertEquals(
        409,
        send(
                "PUT",
                DRAFT + "?draftId=" + oldDraftId,
                2,
                "{\"title\":\"stale\",\"version\":" + oldVersion + "}")
            .statusCode());
    assertEquals(
        409,
        send("DELETE", DRAFT + "?draftId=" + oldDraftId + "&version=" + oldVersion, 2, null)
            .statusCode());
    assertTrue(accept(transfer, 2).statusCode() >= 400);
    assertEquals("new owners draft", data(send("GET", DRAFT, 2, null)).path("title").asString());
  }

  @Test
  void previousOwnersStaleSaveDeleteAndPreviewCannotAffectTheFreshDraft() throws Exception {
    assertEquals(200, accept(create(), 2).statusCode());
    assertEquals(201, send("POST", DRAFT, 2, "{\"title\":\"keep fresh\"}").statusCode());
    assertEquals(404, send("POST", DRAFT, 1, "{}").statusCode());
    assertEquals(
        404,
        send("PUT", DRAFT + "?draftId=" + oldDraftId, 1, "{\"version\":" + oldVersion + "}")
            .statusCode());
    assertEquals(
        404,
        send("DELETE", DRAFT + "?draftId=" + oldDraftId + "&version=" + oldVersion, 1, null)
            .statusCode());
    assertEquals(
        404,
        send("GET", DRAFT + "/preview?draftId=" + oldDraftId + "&version=" + oldVersion, 1, null)
            .statusCode());
    assertEquals("keep fresh", data(send("GET", DRAFT, 2, null)).path("title").asString());
  }

  @Test
  void creatingAndCancellingAPendingTransferPreservesTheDraft() throws Exception {
    var transfer = create();
    assertOriginalDraft();
    assertEquals(
        200,
        send("POST", TRANSFERS + "/" + transfer.path("transferId").asLong() + "/cancel", 1, null)
            .statusCode());
    assertOriginalDraft();
    assertEquals("CANCELLED", transferStatus());
  }

  @ParameterizedTest
  @ValueSource(strings = {"expired", "cancelled", "wrong-recipient", "self"})
  void rejectedAcceptancePreservesOwnershipAndDraft(String reason) throws Exception {
    var transfer = create();
    if (reason.equals("expired"))
      jdbc.update(
          "UPDATE ownership_transfers SET expires_at=?",
          Timestamp.from(Instant.now().minusSeconds(3600)));
    if (reason.equals("cancelled"))
      assertEquals(
          200,
          send("POST", TRANSFERS + "/" + transfer.path("transferId").asLong() + "/cancel", 1, null)
              .statusCode());
    long actor = reason.equals("wrong-recipient") ? 3 : reason.equals("self") ? 1 : 2;
    var response = accept(transfer, actor);
    assertTrue(response.statusCode() >= 400 && response.statusCode() < 500, response.body());
    assertOriginalDraft();
    assertEquals(1L, owner());
    assertEquals(0, count("audit_logs", "action='OWNERSHIP_TRANSFERRED'"));
  }

  @Test
  void acceptanceWithoutAnExistingDraftStillCompletes() throws Exception {
    assertEquals(
        204,
        send("DELETE", DRAFT + "?draftId=" + oldDraftId + "&version=" + oldVersion, 1, null)
            .statusCode());
    assertEquals(200, accept(create(), 2).statusCode());
    assertEquals(2L, owner());
    assertEquals(201, send("POST", DRAFT, 2, "{}").statusCode());
  }

  @Test
  void deletionFailureRollsBackOwnershipAndTransferAndCanBeRetried() throws Exception {
    var transfer = create();
    jdbc.update("INSERT INTO draft_cleanup_guard(draft_id) VALUES(?)", oldDraftId);
    assertEquals(500, accept(transfer, 2).statusCode());
    assertRolledBack();
    jdbc.update("DELETE FROM draft_cleanup_guard");
    assertEquals(200, accept(transfer, 2).statusCode());
    assertEquals(0, count("listing_drafts", "product_id=101"));
  }

  @Test
  void failureAfterDeletionRestoresDraftSelectionsOwnerAndTransferTogether() throws Exception {
    var transfer = create();
    var observed = new AtomicBoolean();
    BEFORE_AUDIT.set(
        () -> {
          assertEquals(0, count("listing_drafts", "product_id=101"));
          assertEquals(0, count("draft_images", "draft_id=" + oldDraftId));
          observed.set(true);
        });
    FAIL_AUDIT.set(true);
    assertEquals(500, accept(transfer, 2).statusCode());
    assertTrue(observed.get(), "Failure must happen after actual draft deletion");
    assertRolledBack();
    FAIL_AUDIT.set(false);
    BEFORE_AUDIT.set(null);
    assertEquals(200, accept(transfer, 2).statusCode());
  }

  @Test
  void concurrentPreviousOwnerSaveCannotRecreateTheDeletedDraft() throws Exception {
    var transfer = create();
    withPausedAcceptance(
        transfer,
        () ->
            async(
                "PUT",
                DRAFT + "?draftId=" + oldDraftId,
                1,
                "{\"title\":\"late old tab\",\"version\":" + oldVersion + "}"),
        404,
        true);
    assertEquals(0, count("listing_drafts", "product_id=101"));
    assertEquals(0, count("draft_images", "draft_id=" + oldDraftId));
  }

  @Test
  void newOwnerCannotWriteBeforeCommitButCanStartFreshAfterwards() throws Exception {
    var transfer = create();
    withPausedAcceptance(
        transfer,
        () ->
            async("POST", DRAFT, 2, "{\"title\":\"concurrent fresh\",\"selectedImageIds\":[301]}"),
        404,
        false);
    assertEquals(
        201,
        send("POST", DRAFT, 2, "{\"title\":\"concurrent fresh\",\"selectedImageIds\":[301]}")
            .statusCode());
    assertEquals("concurrent fresh", data(send("GET", DRAFT, 2, null)).path("title").asString());
    // 중복 완료 알림이 와도 새 소유자의 초안은 제거하지 않는다.
    transaction()
        .executeWithoutResult(status -> context.publishEvent(new OwnershipTransferred(101, 1, 2)));
    assertEquals("concurrent fresh", data(send("GET", DRAFT, 2, null)).path("title").asString());
  }

  @Test
  void cleanupRequiresTheOwnershipTransactionAndCurrentNewOwner() {
    assertThrows(
        IllegalTransactionStateException.class,
        () -> context.publishEvent(new OwnershipTransferred(101, 1, 2)));
    assertThrows(
        RuntimeException.class,
        () ->
            transaction()
                .executeWithoutResult(
                    status -> context.publishEvent(new OwnershipTransferred(101, 1, 2))));
    assertOriginalDraft();
    assertEquals(1L, owner());
  }

  @Test
  void cookieAcceptanceRequiresCsrfBeforeAnyCleanup() throws Exception {
    var transfer = create();
    String body = JSON.writeValueAsString(Map.of("token", transfer.path("rawToken").asString()));
    var request =
        HttpRequest.newBuilder(URI.create(base + TRANSFERS + "/accept"))
            .header("Content-Type", "application/json")
            .header("Cookie", "access_token=" + token(2) + "; XSRF-TOKEN=transfer-csrf")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    assertEquals(
        403, HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    assertOriginalDraft();
    assertEquals(1L, owner());
    assertEquals(
        200,
        HTTP.send(
                request.header("X-XSRF-TOKEN", "transfer-csrf").build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(1, count("listing_drafts", "product_id=101"));
    assertEquals(1L, owner());
    var approval =
        HttpRequest.newBuilder(
                URI.create(
                    base + TRANSFERS + "/" + transfer.path("transferId").asLong() + "/approve"))
            .header("Content-Type", "application/json")
            .header("Cookie", "access_token=" + token(1) + "; XSRF-TOKEN=transfer-csrf")
            .POST(HttpRequest.BodyPublishers.ofString(approvalBody()));
    assertEquals(
        403, HTTP.send(approval.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
    assertEquals(
        200,
        HTTP.send(
                approval.header("X-XSRF-TOKEN", "transfer-csrf").build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(0, count("listing_drafts", "product_id=101"));
  }

  private void withPausedAcceptance(
      JsonNode transfer,
      Callable<CompletableFuture<HttpResponse<String>>> concurrent,
      int expected,
      boolean waitsForCommit)
      throws Exception {
    assertEquals(
        200,
        send(
                "POST",
                TRANSFERS + "/accept",
                2,
                JSON.writeValueAsString(Map.of("token", transfer.path("rawToken").asString())))
            .statusCode());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    BEFORE_AUDIT.set(
        () -> {
          entered.countDown();
          try {
            if (!release.await(10, TimeUnit.SECONDS))
              throw new IllegalStateException("Test gate timed out");
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
          }
        });
    var acceptance =
        async(
            "POST",
            TRANSFERS + "/" + transfer.path("transferId").asLong() + "/approve",
            1,
            approvalBody());
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      var atLock = new CountDownLatch(1);
      AT_PRODUCT_LOCK.set(atLock);
      var contender = concurrent.call();
      assertTrue(
          atLock.await(10, TimeUnit.SECONDS),
          "Concurrent request must reach the real product lock");
      if (waitsForCommit) assertFalse(contender.isDone(), "Transfer still holds the product lock");
      else assertEquals(expected, contender.get(15, TimeUnit.SECONDS).statusCode());
      release.countDown();
      assertEquals(200, acceptance.get(15, TimeUnit.SECONDS).statusCode());
      var result = contender.get(15, TimeUnit.SECONDS);
      assertEquals(expected, result.statusCode(), result.body());
    } finally {
      release.countDown();
      BEFORE_AUDIT.set(null);
      AT_PRODUCT_LOCK.set(null);
    }
  }

  private void assertRolledBack() {
    assertEquals(1L, owner());
    assertEquals("PENDING", transferStatus());
    assertEquals(0, count("ownership_transfers", "completed_at IS NOT NULL"));
    assertEquals(0, count("audit_logs", "action='OWNERSHIP_TRANSFERRED'"));
    assertOriginalDraft();
  }

  private void assertOriginalDraft() {
    assertEquals(
        oldDraftId,
        jdbc.queryForObject("SELECT id FROM listing_drafts WHERE product_id=101", Long.class));
    assertEquals(
        "previous private draft",
        jdbc.queryForObject("SELECT title FROM listing_drafts WHERE product_id=101", String.class));
    assertEquals(2, count("draft_images", "draft_id=" + oldDraftId));
    assertEquals(4, count("product_images", "product_id=101"));
  }

  private static TransactionTemplate transaction() {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  private static long owner() {
    return jdbc.queryForObject("SELECT owner_id FROM products WHERE id=101", Long.class);
  }

  private static String transferStatus() {
    return jdbc.queryForObject(
        "SELECT status FROM ownership_transfers WHERE product_id=101", String.class);
  }

  private static long count(String table, String where) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
  }

  private static JsonNode data(HttpResponse<String> response) {
    return JSON.readTree(response.body()).path("data");
  }

  private static JsonNode create() throws Exception {
    var response = send("POST", TRANSFERS, 1, "{\"productId\":101,\"toUserId\":2}");
    assertEquals(201, response.statusCode(), response.body());
    return data(response);
  }

  private static HttpResponse<String> accept(JsonNode transfer, long user) throws Exception {
    var consent =
        send(
            "POST",
            TRANSFERS + "/accept",
            user,
            JSON.writeValueAsString(Map.of("token", transfer.path("rawToken").asString())));
    if (consent.statusCode() != 200) return consent;
    return send(
        "POST",
        TRANSFERS + "/" + transfer.path("transferId").asLong() + "/approve",
        1,
        approvalBody());
  }

  private static String approvalBody() {
    return JSON.writeValueAsString(
        Map.of(
            "expectedRecipientId",
            2L,
            "expectedFactsVersion",
            jdbc.queryForObject("SELECT facts_version FROM products WHERE id=101", Long.class)));
  }

  private static HttpResponse<String> send(String method, String path, long user, String body)
      throws Exception {
    return async(method, path, user, body).get(25, TimeUnit.SECONDS);
  }

  private static CompletableFuture<HttpResponse<String>> async(
      String method, String path, long user, String body) throws Exception {
    return HTTP.sendAsync(
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token(user))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String token(long user) throws Exception {
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
