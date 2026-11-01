package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.ai.repository.ListingSummaryRepository;
import org.example.springtestci.ai.service.AiSuggestionRateLimiter;
import org.example.springtestci.ai.service.ListingSuggestionSource;
import org.example.springtestci.ai.service.ListingSummaryGateway;
import org.example.springtestci.ai.service.ListingSummaryProcessor;
import org.example.springtestci.ai.service.ListingSummaryStore;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.history.dto.CorrectHistoryEventRequest;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.dto.VoidHistoryEventRequest;
import org.example.springtestci.history.entity.HistoryEventType;
import org.example.springtestci.history.service.HistoryChangeReviewRecorder;
import org.example.springtestci.history.service.HistoryService;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 정식 Flyway·이력 API·조회 어댑터·JWT·HTTP를 연결한다. 외부 AI는 호출하지 않는다. */
class ListingHistoryIntegrationHttpTest {
  private static final String SECRET = "history-integration-only-jwt-secret-at-least-32-bytes";
  private static final String PUBLICATION = "/api/v1/products/101/listing-draft/publication";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final AtomicReference<String> AI_INPUT = new AtomicReference<>();
  private static final AtomicInteger AI_CALLS = new AtomicInteger();
  private static final AtomicBoolean AI_FAILURE = new AtomicBoolean();
  private static final AtomicInteger REVIEW_CALLS = new AtomicInteger();
  private static final AtomicBoolean REVIEW_FAILURE = new AtomicBoolean();
  private static final AtomicBoolean REFRESH_LOOKUP_FAILURE = new AtomicBoolean();
  private static final AtomicBoolean REVIEW_IN_TRANSACTION = new AtomicBoolean();
  private static final AtomicReference<Long> REVIEW_VERSION = new AtomicReference<>();
  private static final AtomicReference<JsonNode> REVIEW_SNAPSHOT = new AtomicReference<>();
  private static final AiSuggestionRateLimiter LIMITS = mock(AiSuggestionRateLimiter.class);
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static String base, schema;
  private static Path files;

  /** 이력·인증·DB·갱신은 실제 구현을 쓰고 AI·Redis 실행권·거래 검토 포트는 시험 대역으로 대체한다. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class AiFixtures {
    @Bean
    @Primary
    AiSuggestionRateLimiter historySummaryLimits() {
      return LIMITS;
    }

    @Bean
    @Primary
    ListingSummaryGateway historySummaryGateway() {
      return new ListingSummaryGateway() {
        public boolean configured() {
          return true;
        }

        /** 외부 호출 없이 AI 입력·호출 횟수를 기록하고 모델 실패를 재현한다. */
        public List<String> summarize(String input) {
          AI_INPUT.set(input);
          AI_CALLS.incrementAndGet();
          if (AI_FAILURE.get()) throw new CommonException(ErrorCode.AI_UNAVAILABLE);
          return List.of("18GB 메모리와 512GB SSD를 갖춘 제품입니다.");
        }
      };
    }

    /** 이력 저장 SQL과 분리해 커밋 이후 요약 대상 조회 장애만 재현한다. */
    @Bean
    @Primary
    ListingSummaryRepository historySummaryRepository(JdbcTemplate jdbc) {
      return new ListingSummaryRepository(jdbc) {
        @Override
        public List<Long> publicListingsForProduct(long productId) {
          if (REFRESH_LOOKUP_FAILURE.get()) {
            throw new IllegalStateException("Summary refresh lookup fixture failure");
          }
          return super.publicListingsForProduct(productId);
        }
      };
    }

    /** 실제 HistoryService가 같은 트랜잭션에서 거래 검토 포트를 호출하는지 기록한다. */
    @Bean
    @Primary
    HistoryChangeReviewRecorder historyChangeReviews() {
      return (productId, eventId, factsVersion, snapshot) -> {
        REVIEW_CALLS.incrementAndGet();
        REVIEW_IN_TRANSACTION.set(TransactionSynchronizationManager.isActualTransactionActive());
        REVIEW_VERSION.set(factsVersion);
        REVIEW_SNAPSHOT.set(snapshot);
        if (REVIEW_FAILURE.get()) throw new IllegalStateException("Trade review fixture failure");
      };
    }
  }

  /** 격리 DB의 실제 API를 띄우고 외부 포트를 대체해 데이터 변경과 커밋 후 접수를 검증한다. */
  @BeforeAll
  static void start() throws Exception {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    boolean postgres = url != null && !url.isBlank();
    if (postgres && !url.contains("sale_ai_test"))
      throw new IllegalStateException("Use a dedicated sale_ai_test database only");
    schema = postgres ? "history_http_" + Long.toUnsignedString(System.nanoTime()) : "public";
    url =
        postgres
            ? url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema
            : "jdbc:h2:mem:listing_real_history;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    files = Files.createTempDirectory("listing-real-history-http-");
    var app = new SpringApplication(SpringTestCiApplication.class, AiFixtures.class);
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
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--app.features.listing-history.enabled=true",
            "--app.ai.summary.enabled=true",
            "--app.ai.summary.refresh-debounce-ms=0",
            "--app.ai.summary.initial-delay-ms=3600000",
            "--app.ai.listing.api-key=",
            "--app.jwt.secret=" + SECRET,
            "--app.storage.local-root=" + files);
    jdbc = context.getBean(JdbcTemplate.class);
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'history-owner@fixture.invalid','unused','h-owner','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'history-former@fixture.invalid','unused','h-former','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) {
      if (!"public".equals(schema)) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      context.close();
    }
    if (files != null) {
      if (!files
              .toAbsolutePath()
              .normalize()
              .getParent()
              .equals(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
          || !files.getFileName().toString().startsWith("listing-real-history-http-"))
        throw new IllegalStateException("Unexpected test cleanup directory");
      try (var paths = Files.walk(files)) {
        for (var path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  @BeforeEach
  void reset() {
    AI_INPUT.set(null);
    AI_CALLS.set(0);
    AI_FAILURE.set(false);
    REVIEW_CALLS.set(0);
    REVIEW_FAILURE.set(false);
    REFRESH_LOOKUP_FAILURE.set(false);
    REVIEW_IN_TRANSACTION.set(false);
    REVIEW_VERSION.set(null);
    REVIEW_SNAPSHOT.set(null);
    org.mockito.Mockito.reset(LIMITS);
    when(LIMITS.acquire(anyLong()))
        .thenReturn(new AiSuggestionRateLimiter.Lease("history-test", "history-test"));
    jdbc.update("DELETE FROM event_documents");
    jdbc.update("DELETE FROM audit_logs");
    jdbc.update("DELETE FROM product_events");
    jdbc.update("DELETE FROM listing_images");
    jdbc.update("DELETE FROM listings");
    jdbc.execute("ALTER TABLE product_events ALTER COLUMN id RESTART WITH 1000");
    jdbc.execute("ALTER TABLE audit_logs ALTER COLUMN id RESTART WITH 1000");
    jdbc.update("UPDATE products SET owner_id=1,archived_at=NULL,facts_version=7 WHERE id=101");
    jdbc.update("UPDATE laptop_details SET ram_gb=18 WHERE product_id=101");
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES(11,101,1,'실제 이력 연결 검증',1200000,4,'판매 조건','ACTIVE',CURRENT_TIMESTAMP,9001,0,1)");
    event(1, 101, 2L, "PURCHASE", "2024-02-12", "VALID", null);
    event(2, 101, 1L, "INSPECTION", "2025-05-20", "VALID", null);
    event(3, 101, 2L, "REPAIR", "2025-04-02", "VOID", null);
    event(4, 101, null, "TRANSFER", "2024-06-15", "VALID", 55L);
    event(9, 201, 2L, "REPAIR", "2025-01-01", "VALID", null);
    change(101, 101, 2, "HISTORY_CORRECTED");
    change(102, 101, 2, "HISTORY_CORRECTED");
    change(103, 101, 3, "HISTORY_VOIDED");
    change(104, 101, 2, "PRODUCT_UPDATED");
    change(105, 201, 2, "HISTORY_CORRECTED");
  }

  @Test
  void ownerPreviewReadsRealPreviousOwnerVoidAndAllCorrectionMetadata() throws Exception {
    var response = get(PUBLICATION, "1");
    var history = data(response).get("history");
    assertEquals("READY", history.get("status").asString());
    assertFalse(history.get("sample").asBoolean());
    assertEquals(3, history.get("validCount").asInt());
    assertEquals(1, history.get("voidCount").asInt());
    var items = history.get("items");
    assertEquals(List.of(2L, 3L, 4L, 1L), ids(items));
    assertEquals(List.of(102L, 101L), correctionIds(items.get(0).get("corrections")));
    assertEquals("EXCLUDED", items.get(0).get("detailsStatus").asString());
    assertEquals("제품 점검", items.get(0).get("title").asString());
    assertTrue(items.get(0).get("content").isNull());
    assertTrue(items.get(0).get("corrections").get(0).get("reason").isNull());
    assertEquals(
        "EXCLUDED", items.get(0).get("corrections").get(0).get("detailsStatus").asString());
    assertEquals("VOID", items.get(1).get("status").asString());
    assertTrue(items.get(1).get("previousOwner").asBoolean());
    assertTrue(items.get(2).get("systemGenerated").asBoolean());
    assertFalse(items.get(2).get("previousOwner").asBoolean());
    assertPrivateFieldsAbsent(response.body());
  }

  @Test
  void publicDetailsReachBuyerAndAiAndRefreshOnCorrectionWithoutAdminApproval() throws Exception {
    jdbc.update(
        "UPDATE product_events SET title='배터리 교체',content='교체 후 충전 동작 확인',service_provider='공식 서비스센터',replaced_part='배터리',public_text_reviewed=FALSE WHERE id IN (1,2,3)");
    jdbc.update(
        "UPDATE audit_logs SET reason='발생일 오기입 정정',public_reason_reviewed=FALSE WHERE id=101");
    var preview = data(get(PUBLICATION, "1")).get("history");
    var buyerResponse = get("/api/v1/listings/11", "2");
    assertEquals(preview, data(buyerResponse).get("history"));
    var item = preview.get("items").get(0);
    assertEquals("배터리 교체", item.get("title").asString());
    assertEquals("교체 후 충전 동작 확인", item.get("content").asString());
    assertEquals("공식 서비스센터", item.get("serviceProvider").asString());
    assertEquals("배터리", item.get("replacedPart").asString());
    assertEquals("REVIEWED", item.get("detailsStatus").asString());
    assertEquals("EXCLUDED", item.at("/corrections/0/detailsStatus").asString());
    assertEquals("발생일 오기입 정정", item.at("/corrections/1/reason").asString());
    assertTrue(preview.at("/items/3/previousOwner").asBoolean());
    assertEquals("교체 후 충전 동작 확인", preview.at("/items/3/content").asString());
    assertEquals("VOID", preview.at("/items/1/status").asString());
    assertPrivateFieldsAbsent(buyerResponse.body());

    readySummary();
    assertEquals("교체 후 충전 동작 확인", aiEvent(2).get("content").asString());
    assertEquals("교체 후 충전 동작 확인", aiEvent(1).get("content").asString());
    assertTrue(aiEvent(3).get("content").isNull());

    // 같은 제품 버전의 과거 메타데이터 전용 요약도 새 입력 정책으로 갱신할 수 있어야 한다.
    jdbc.update("UPDATE listings SET summary_format_version=1 WHERE id=11");
    assertEquals(List.of(11L), summaries().staleListings());
    var legacy = data(get("/api/v1/listings/11/summary", "1"));
    assertTrue(legacy.get("text").isNull());
    assertTrue(legacy.get("canRequest").asBoolean());
    context.getBean(ListingSummaryStore.class).refresh(11);
    processPending();
    assertEquals(2, summaries().read(11).formatVersion());
    assertTrue(summaries().staleListings().isEmpty());

    data(
        write(
            "PATCH",
            "/api/v1/products/101/events/2",
            """
        {"occurredOn":"2025-05-20","title":"배터리 점검",
         "content":"충전과 방전 동작 점검 완료","serviceProvider":"공식 서비스센터",
         "replacedPart":"배터리","reason":"점검 내용 오기입 정정"}
        """,
            "1"));
    assertEquals(8, factsVersion());
    var corrected = data(get("/api/v1/listings/11", "2")).at("/history/items/0");
    assertEquals("충전과 방전 동작 점검 완료", corrected.get("content").asString());
    assertEquals("점검 내용 오기입 정정", corrected.at("/corrections/0/reason").asString());
    processPending();
    assertEquals("충전과 방전 동작 점검 완료", aiEvent(2).get("content").asString());

    data(
        write("POST", "/api/v1/products/101/events/2/void", "{\"reason\":\"중복 등록으로 무효 처리\"}", "1"));
    var voided = data(get("/api/v1/listings/11", "2")).at("/history/items/0");
    assertEquals("VOID", voided.get("status").asString());
    assertEquals("중복 등록으로 무효 처리", voided.at("/corrections/0/reason").asString());
    processPending();
    assertEquals("VOID", aiEvent(2).get("status").asString());
    assertTrue(aiEvent(2).get("content").isNull());
    assertPrivateFieldsAbsent(AI_INPUT.get());

    // 판매글을 읽을 수 있는 구매자에게도 문서 조회 권한은 주어지지 않는다.
    assertEquals(403, get("/api/v1/products/101/documents", "2").statusCode());
    assertEquals(200, get("/api/v1/products/101/documents", "1").statusCode());
    assertFalse(
        jdbc.queryForObject(
            "SELECT public_text_reviewed FROM product_events WHERE id=2", Boolean.class));
    assertEquals(
        987654321L, jdbc.queryForObject("SELECT cost FROM product_events WHERE id=1", Long.class));
  }

  @Test
  void historyApiCreatesCorrectsAndVoidsTheRecordReadByListingPublication() throws Exception {
    jdbc.update("DELETE FROM audit_logs");
    jdbc.update("DELETE FROM product_events");
    String eventsPath = "/api/v1/products/101/events";
    String creation =
        """
        {"eventType":"INSPECTION","occurredOn":"2025-05-20","title":"고객명: PRIVATE_HISTORY_TITLE",
         "content":"private-person@fixture.invalid","serviceProvider":"담당자: PRIVATE_SERVICE_PROVIDER",
         "cost":987654321,"documentIds":[]}
        """;
    assertEquals(404, write("POST", eventsPath, creation, "2").statusCode());
    var created = write("POST", eventsPath, creation, "1");
    assertEquals(201, created.statusCode(), created.body());
    long eventId = JSON.readTree(created.body()).at("/data/eventId").asLong();
    var initialResponse = get(PUBLICATION, "1");
    var history = data(initialResponse).get("history");
    assertFalse(history.get("sample").asBoolean());
    assertEquals(List.of(eventId), ids(history.get("items")));
    assertEquals("INSPECTION", history.at("/items/0/eventType").asString());
    assertPrivateFieldsAbsent(initialResponse.body());

    data(
        write(
            "PATCH",
            eventsPath + "/" + eventId,
            """
            {"occurredOn":"2025-05-21","title":"고객명: PRIVATE_CORRECTED_TITLE",
             "content":"private-person@fixture.invalid","reason":"고객명: PRIVATE_CHANGE_REASON"}
            """,
            "1"));
    var correctedResponse = get(PUBLICATION, "1");
    var corrected = data(correctedResponse).at("/history/items/0");
    assertEquals("2025-05-21", corrected.get("occurredOn").asString());
    assertEquals(1, corrected.get("corrections").size());
    assertPrivateFieldsAbsent(correctedResponse.body());

    data(
        write(
            "POST",
            eventsPath + "/" + eventId + "/void",
            "{\"reason\":\"고객명: PRIVATE_VOID_REASON\"}",
            "1"));
    var voidedResponse = get(PUBLICATION, "1");
    var voided = data(voidedResponse).get("history");
    assertEquals(0, voided.get("validCount").asInt());
    assertEquals(1, voided.get("voidCount").asInt());
    assertEquals("VOID", voided.at("/items/0/status").asString());
    assertEquals(2, voided.at("/items/0/corrections").size());
    assertPrivateFieldsAbsent(voidedResponse.body());
  }

  /** 브라우저처럼 쿠키 인증·응답 쿠키 갱신을 사용해 조회 뒤 첫 쓰기와 연속 쓰기를 검증한다. */
  @Test
  void cookieAuthenticatedHistoryFlowAlwaysHasACsrfCookieForTheNextRequest() throws Exception {
    var browser = new CookieBrowser();
    data(browser.read(PUBLICATION));
    assertFalse(browser.csrfToken().isBlank());

    var created =
        browser.write(
            "POST", "/api/v1/products/101/events", JSON.writeValueAsString(inspectionRequest()));
    assertEquals(201, created.statusCode(), created.body());
    long id = JSON.readTree(created.body()).at("/data/eventId").asLong();
    assertEquals(8, factsVersion());
    assertFalse(browser.csrfToken().isBlank());

    data(browser.read("/api/v1/products/101/events"));
    data(browser.write("PATCH", "/api/v1/products/101/events/" + id, correctionJson(null)));
    assertEquals(9, factsVersion());
    assertFalse(browser.csrfToken().isBlank());

    var voided =
        browser.write(
            "POST", "/api/v1/products/101/events/" + id + "/void", "{\"reason\":\"PRIVATE_VOID\"}");
    assertEquals("VOID", data(voided).get("status").asString());
    assertEquals(10, factsVersion());
    assertFalse(browser.csrfToken().isBlank());
    data(browser.read(PUBLICATION));
    assertFalse(browser.csrfToken().isBlank());
  }

  @Test
  void cookieAuthenticatedWritesStillRejectMissingOrIncorrectCsrfHeaders() throws Exception {
    var browser = new CookieBrowser();
    String path = "/api/v1/products/101/events";
    String body = JSON.writeValueAsString(inspectionRequest());
    assertEquals(403, browser.send("POST", path, body, null).statusCode());
    assertEquals(403, browser.send("POST", path, body, "incorrect-csrf-token").statusCode());
    assertEquals(7, factsVersion());
    assertEquals(4, data(browser.read("/api/v1/products/101/events")).size());

    var created = browser.write("POST", path, body);
    assertEquals(201, created.statusCode(), created.body());
    assertEquals(8, factsVersion());
    assertFalse(browser.csrfToken().isBlank());
  }

  @Test
  void addingAnInspectionQueuesItsCommittedVersionWithoutCallingAiInsideTheRequest()
      throws Exception {
    readySummary();
    var before = summaries().read(11).attemptId();
    long id = createInspection();
    assertEquals(8, factsVersion());
    assertNotEquals(before, summaries().read(11).attemptId());
    assertEquals("QUEUED", summaries().read(11).status().name());
    assertEquals(8L, summaries().read(11).productVersion());
    assertNull(summaries().read(11).text());
    assertEquals(1, AI_CALLS.get());
    processPending();
    assertEquals("INSPECTION", aiEvent(id).get("type").asString());
    assertEquals("READY", summaries().read(11).status().name());
    assertPrivateFieldsAbsent(AI_INPUT.get());
  }

  /** 거래 검토 기록과 AI 접수가 단일 버전을 공유하고 외부 AI는 커밋 후 한 번만 실행된다. */
  @Test
  void creationRecordsTradeReviewAndQueuesOneSummaryForTheSameVersion() throws Exception {
    readySummary();
    long id = createInspection();
    assertEquals(1, REVIEW_CALLS.get());
    assertTrue(REVIEW_IN_TRANSACTION.get());
    assertEquals(8L, REVIEW_VERSION.get());
    assertEquals(8, factsVersion());
    assertEquals(8L, summaries().read(11).productVersion());
    assertEquals("QUEUED", summaries().read(11).status().name());
    assertEquals(1, summaries().pending().size());
    assertEquals(1, AI_CALLS.get());
    assertEquals("INSPECTION", REVIEW_SNAPSHOT.get().get("eventType").asString());
    assertPrivateFieldsAbsent(JSON.writeValueAsString(REVIEW_SNAPSHOT.get()));
    processPending();
    assertEquals(2, AI_CALLS.get());
    assertEquals("INSPECTION", aiEvent(id).get("type").asString());
    assertEquals("READY", summaries().read(11).status().name());
  }

  /** 거래 검토 기록 실패 시 이력·버전이 롤백되고 AI 요약 갱신도 접수되지 않는다. */
  @Test
  void failedTradeReviewRecordingRollsBackHistoryVersionAndSummaryRefresh() throws Exception {
    readySummary();
    var attempt = summaries().read(11).attemptId();
    REVIEW_FAILURE.set(true);
    var response =
        write(
            "POST",
            "/api/v1/products/101/events",
            JSON.writeValueAsString(inspectionRequest()),
            "1");
    assertEquals(500, response.statusCode(), response.body());
    assertEquals(1, REVIEW_CALLS.get());
    assertTrue(REVIEW_IN_TRANSACTION.get());
    assertEquals(7, factsVersion());
    assertEquals(
        0,
        jdbc.queryForObject("SELECT COUNT(*) FROM product_events WHERE id>=1000", Integer.class));
    assertEquals(attempt, summaries().read(11).attemptId());
    assertEquals("READY", summaries().read(11).status().name());
    assertTrue(summaries().pending().isEmpty());
    assertEquals(1, AI_CALLS.get());
  }

  @Test
  void correctingAndVoidingAReservedListingsHistoryRefreshesTheActualAiInput() throws Exception {
    readySummary();
    jdbc.update("UPDATE listings SET status='RESERVED' WHERE id=11");
    data(write("PATCH", "/api/v1/products/101/events/2", correctionJson(null), "1"));
    assertEquals(8, factsVersion());
    assertEquals(8L, summaries().read(11).productVersion());
    processPending();
    assertTrue(aiEvent(2).get("corrected").asBoolean());
    assertEquals("2025-05-21", aiEvent(2).get("occurredOn").asString());
    data(write("POST", "/api/v1/products/101/events/2/void", "{\"reason\":\"PRIVATE_VOID\"}", "1"));
    assertEquals(9, factsVersion());
    processPending();
    assertEquals("VOID", aiEvent(2).get("status").asString());
    assertTrue(aiEvent(2).get("content").isNull());
    assertEquals(9L, summaries().read(11).productVersion());
    assertEquals("READY", summaries().read(11).status().name());
    assertPrivateFieldsAbsent(AI_INPUT.get());
  }

  @Test
  void specificationCreationCorrectionAndVoidEachIncrementTheVersionOnlyOnce() throws Exception {
    readySummary();
    var request =
        new CreateHistoryEventRequest(
            HistoryEventType.SPEC_CHANGE,
            LocalDate.of(2025, 5, 20),
            "PRIVATE_SPEC_TITLE",
            null,
            null,
            null,
            null,
            Map.of("ramGb", 32),
            List.of());
    var created =
        write("POST", "/api/v1/products/101/events", JSON.writeValueAsString(request), "1");
    assertEquals(201, created.statusCode(), created.body());
    long id = JSON.readTree(created.body()).at("/data/eventId").asLong();
    assertEquals(8, factsVersion());
    assertEquals(32, ram());
    data(
        write(
            "PATCH",
            "/api/v1/products/101/events/" + id,
            correctionJson(Map.of("ramGb", 64)),
            "1"));
    assertEquals(9, factsVersion());
    assertEquals(64, ram());
    data(
        write(
            "POST",
            "/api/v1/products/101/events/" + id + "/void",
            "{\"reason\":\"PRIVATE_VOID\"}",
            "1"));
    assertEquals(10, factsVersion());
    assertEquals(18, ram());
    assertEquals(10L, summaries().read(11).productVersion());
    processPending();
    assertEquals("VOID", aiEvent(id).get("status").asString());
    assertEquals("READY", summaries().read(11).status().name());
  }

  @Test
  void rolledBackCreationCorrectionAndVoidDoNotInvalidateOrQueueTheStoredSummary()
      throws Exception {
    readySummary();
    var service = context.getBean(HistoryService.class);
    assertHistoryRollback(() -> service.create(1, 101, inspectionRequest()));
    assertHistoryRollback(() -> service.correct(1, 101, 2, correctionRequest(null)));
    assertHistoryRollback(
        () -> service.voidEvent(1, 101, 2, new VoidHistoryEventRequest("PRIVATE_VOID")));
    assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM product_events", Integer.class));
    assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class));
    assertEquals(
        "VALID", jdbc.queryForObject("SELECT status FROM product_events WHERE id=2", String.class));
    assertEquals(1, AI_CALLS.get());
  }

  @Test
  void anAiFailureDoesNotUndoTheSuccessfullyCommittedHistory() throws Exception {
    readySummary();
    AI_FAILURE.set(true);
    long id = createInspection();
    processPending();
    assertEquals("FAILED", summaries().read(11).status().name());
    assertEquals(8, factsVersion());
    assertEquals(
        1,
        jdbc.queryForObject("SELECT COUNT(*) FROM product_events WHERE id=?", Integer.class, id));
    assertTrue(ids(data(get(PUBLICATION, "1")).at("/history/items")).contains(id));
  }

  @Test
  void anUnavailableRefreshLookupDoesNotTurnACommittedHistoryRequestIntoAnError() throws Exception {
    readySummary();
    var attempt = summaries().read(11).attemptId();
    REFRESH_LOOKUP_FAILURE.set(true);
    long id;
    try {
      id = createInspection();
    } finally {
      REFRESH_LOOKUP_FAILURE.set(false);
    }
    assertEquals(8, factsVersion());
    assertEquals(
        1,
        jdbc.queryForObject("SELECT COUNT(*) FROM product_events WHERE id=?", Integer.class, id));
    assertEquals(attempt, summaries().read(11).attemptId());
    assertTrue(summaries().staleListings().contains(11L));
  }

  @Test
  void aHistoryChangeDuringGenerationCannotBeOverwrittenByThePreviousJob() throws Exception {
    readySummary();
    long id = createInspection();
    var oldJob = context.getBean(ListingSummaryStore.class).claim(summaries().pending().get(0));
    assertNotNull(oldJob);
    data(write("PATCH", "/api/v1/products/101/events/" + id, correctionJson(null), "1"));
    var latestAttempt = summaries().read(11).attemptId();
    context.getBean(ListingSummaryStore.class).complete(oldJob, "18GB 메모리 모델입니다.");
    assertEquals(latestAttempt, summaries().read(11).attemptId());
    assertEquals("QUEUED", summaries().read(11).status().name());
    assertNull(summaries().read(11).text());
    processPending();
    assertEquals(9L, summaries().read(11).productVersion());
    assertEquals("2025-05-21", aiEvent(id).get("occurredOn").asString());
  }

  @Test
  void aProductWithoutAnOpenListingStillSavesHistoryAndVersionWithoutAiWork() throws Exception {
    jdbc.update("UPDATE listings SET status='CANCELLED',active_slot=NULL WHERE id=11");
    createInspection();
    assertEquals(8, factsVersion());
    assertTrue(summaries().pending().isEmpty());
    assertEquals(0, AI_CALLS.get());
  }

  @Test
  void memberDetailUsesTheSameRealHistoryAndKeepsOwnershipBasedManageRights() throws Exception {
    var owner = data(get(PUBLICATION, "1")).get("history");
    var buyerResponse = get("/api/v1/listings/11", "2");
    var buyer = data(buyerResponse);
    assertEquals(owner, buyer.get("history"));
    assertFalse(buyer.get("canManage").asBoolean());
    assertTrue(data(get("/api/v1/listings/11", "1")).get("canManage").asBoolean());
    assertPrivateFieldsAbsent(buyerResponse.body());
  }

  @Test
  void aiCaptureIncludesTheWholeHistoryWithoutCallingAnExternalProvider() throws Exception {
    for (long id = 20; id < 44; id++) event(id, 101, 2L, "INSPECTION", "2025-01-01", "VALID", null);
    assertEquals(1, data(get(PUBLICATION + "?page=0&size=1", "1")).at("/history/items").size());
    var snapshot = context.getBean(ListingSuggestionSource.class).capture(101, 1);
    var value = JSON.readTree(snapshot.json());
    assertEquals(28, value.get("history").size());
    assertFalse(snapshot.sample());
    assertTrue(value.get("history").get(0).get("corrected").asBoolean());
    assertFalse(value.get("history").get(0).get("detailsReviewed").asBoolean());
    assertPrivateFieldsAbsent(snapshot.json());
    assertEquals(
        7L, jdbc.queryForObject("SELECT facts_version FROM products WHERE id=101", Long.class));
    assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class));
  }

  @Test
  void authenticationOwnershipAndEndedListingChecksStillApply() throws Exception {
    assertEquals(401, get(PUBLICATION, null).statusCode());
    assertEquals(404, get(PUBLICATION, "2").statusCode());
    assertEquals(401, get("/api/v1/listings/11", null).statusCode());
    jdbc.update("UPDATE listings SET status='SOLD',active_slot=NULL WHERE id=11");
    assertEquals(404, get("/api/v1/listings/11", "2").statusCode());
    jdbc.update("UPDATE products SET owner_id=2 WHERE id=101");
    assertEquals(404, get(PUBLICATION, "1").statusCode());
  }

  @Test
  void anActuallyEmptyHistoryIsReadyAndDifferentFromUnavailable() throws Exception {
    var result = data(get("/api/v1/products/102/listing-draft/publication", "1")).get("history");
    assertEquals("READY", result.get("status").asString());
    assertEquals(0, result.get("items").size());
    assertFalse(result.get("sample").asBoolean());
  }

  @Test
  void anUndeployedSourceDoesNotPoisonThePostgresCallingTransaction() throws Exception {
    jdbc.execute("ALTER TABLE audit_logs RENAME TO hidden_history_audit");
    try {
      var result = data(get(PUBLICATION, "1"));
      assertEquals("UNAVAILABLE", result.at("/history/status").asString());
      assertEquals("MacBook Pro 14", result.at("/product/modelName").asString());
    } finally {
      jdbc.execute("ALTER TABLE hidden_history_audit RENAME TO audit_logs");
    }
    assertEquals("READY", data(get(PUBLICATION, "1")).at("/history/status").asString());
  }

  @Test
  void jdbcReaderRequiresTheCallingServicesTransaction() {
    assertThrows(
        IllegalTransactionStateException.class,
        () -> context.getBean(ListingHistoryReader.class).read(101));
  }

  private static void event(
      long id, long product, Long author, String type, String date, String status, Long transfer) {
    jdbc.update(
        "INSERT INTO product_events(id,product_id,author_id,event_type,occurred_on,title,content,service_provider,cost,replaced_part,status,transfer_request_id,created_at) VALUES(?,?,?,?,CAST(? AS DATE),?,?,?,?,?,?,?,CAST('2026-10-01T00:00:00Z' AS TIMESTAMP WITH TIME ZONE))",
        id,
        product,
        author,
        type,
        date,
        "고객명: PRIVATE_HISTORY_TITLE",
        "private-person@fixture.invalid",
        "담당자: PRIVATE_SERVICE_PROVIDER",
        987654321L,
        "시리얼: PRIVATE_REPLACED_PART",
        status,
        transfer);
  }

  private static void change(long id, long product, long event, String action) {
    jdbc.update(
        "INSERT INTO audit_logs(id,product_id,actor_id,target_id,event_id,action,reason,before_facts,after_facts,created_at) VALUES(?,?,1,?,?,?,'고객명: PRIVATE_CHANGE_REASON','PRIVATE_BEFORE_FACTS','PRIVATE_AFTER_FACTS',CAST('2026-10-02T00:00:00Z' AS TIMESTAMP WITH TIME ZONE))",
        id,
        product,
        event,
        event,
        action);
  }

  private static void assertPrivateFieldsAbsent(String value) {
    for (String forbidden :
        List.of(
            "PRIVATE_",
            "private-person@fixture.invalid",
            "987654321",
            "documentIds",
            "authorId",
            "specsBefore",
            "specsAfter",
            "\"cost\"")) assertFalse(value.contains(forbidden), forbidden);
  }

  private static ListingSummaryRepository summaries() {
    return context.getBean(ListingSummaryRepository.class);
  }

  private static void readySummary() {
    context.getBean(ListingSummaryStore.class).enqueue(11, null);
    processPending();
    assertEquals("READY", summaries().read(11).status().name());
  }

  private static void processPending() {
    var pending = summaries().pending();
    assertEquals(1, pending.size());
    context.getBean(ListingSummaryProcessor.class).process(pending.get(0));
  }

  private static long factsVersion() {
    return jdbc.queryForObject("SELECT facts_version FROM products WHERE id=101", Long.class);
  }

  private static int ram() {
    return jdbc.queryForObject(
        "SELECT ram_gb FROM laptop_details WHERE product_id=101", Integer.class);
  }

  private static CreateHistoryEventRequest inspectionRequest() {
    return new CreateHistoryEventRequest(
        HistoryEventType.INSPECTION,
        LocalDate.of(2025, 5, 20),
        "고객명: PRIVATE_TEST_TITLE",
        "private-person@fixture.invalid",
        null,
        null,
        null,
        null,
        List.of());
  }

  private static long createInspection() throws Exception {
    var response =
        write(
            "POST",
            "/api/v1/products/101/events",
            JSON.writeValueAsString(inspectionRequest()),
            "1");
    assertEquals(201, response.statusCode(), response.body());
    return JSON.readTree(response.body()).at("/data/eventId").asLong();
  }

  private static CorrectHistoryEventRequest correctionRequest(Map<String, Object> specs) {
    return new CorrectHistoryEventRequest(
        LocalDate.of(2025, 5, 21),
        "고객명: PRIVATE_CORRECTED_TITLE",
        null,
        null,
        null,
        null,
        specs,
        "고객명: PRIVATE_CORRECTION_REASON");
  }

  private static String correctionJson(Map<String, Object> specs) {
    return JSON.writeValueAsString(correctionRequest(specs));
  }

  private static JsonNode aiEvent(long eventId) {
    for (var item : JSON.readTree(AI_INPUT.get()).at("/productAndHistory/history")) {
      if (item.get("eventId").asLong() == eventId) return item;
    }
    throw new AssertionError("AI input is missing event " + eventId);
  }

  private static void assertHistoryRollback(Runnable change) {
    var attempt = summaries().read(11).attemptId();
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    tx.executeWithoutResult(
        status -> {
          change.run();
          assertEquals(8, factsVersion());
          assertEquals(attempt, summaries().read(11).attemptId());
          status.setRollbackOnly();
        });
    assertEquals(7, factsVersion());
    assertEquals(attempt, summaries().read(11).attemptId());
    assertEquals("READY", summaries().read(11).status().name());
    assertTrue(summaries().pending().isEmpty());
  }

  private static List<Long> ids(JsonNode items) {
    var result = new java.util.ArrayList<Long>();
    items.forEach(value -> result.add(value.get("eventId").asLong()));
    return result;
  }

  private static List<Long> correctionIds(JsonNode items) {
    var result = new java.util.ArrayList<Long>();
    items.forEach(value -> result.add(value.get("correctionId").asLong()));
    return result;
  }

  private static JsonNode data(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body()).get("data");
  }

  private static HttpResponse<String> get(String path, String user) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).GET();
    if (user != null) request.header("Authorization", "Bearer " + token(user));
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> write(String method, String path, String body, String user)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token(user))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** 쿠키를 응답마다 반영하는 HTTP 클라이언트로, Swagger와 일반 화면의 인증 방식을 재현한다. */
  private static final class CookieBrowser {
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    CookieBrowser() throws Exception {
      assertEquals(200, read("/swagger-ui/index.html").statusCode());
      assertFalse(csrfToken().isBlank());
      var access = new HttpCookie("access_token", token("1"));
      access.setVersion(0);
      access.setPath("/");
      access.setHttpOnly(true);
      cookies.getCookieStore().add(URI.create(base), access);
    }

    /** 인증 응답에서 교체된 쿠키를 읽고, 다음 요청의 토큰이 사라졌다면 즉시 실패시킨다. */
    String csrfToken() {
      return cookies.getCookieStore().getCookies().stream()
          .filter(cookie -> cookie.getName().equals("XSRF-TOKEN") && !cookie.hasExpired())
          .map(HttpCookie::getValue)
          .filter(value -> !value.isBlank())
          .findFirst()
          .orElseThrow(() -> new AssertionError("The next browser request has no CSRF cookie"));
    }

    HttpResponse<String> read(String path) throws Exception {
      return send("GET", path, null, null);
    }

    HttpResponse<String> write(String method, String path, String body) throws Exception {
      return send(method, path, body, csrfToken());
    }

    /** 브라우저 쿠키를 유지하며 요청하고, CSRF 헤더의 정상·누락·불일치 조건을 구분한다. */
    HttpResponse<String> send(String method, String path, String body, String csrf)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create(base + path))
              .timeout(Duration.ofSeconds(15))
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(body));
      if (body != null) request.header("Content-Type", "application/json");
      if (csrf != null) request.header("X-XSRF-TOKEN", csrf);
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
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
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return content
        + "."
        + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
  }
}
