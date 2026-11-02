package org.example.springtestci.admin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.admin.repository.AdminReadRepository;
import org.example.springtestci.admin.service.*;
import org.example.springtestci.common.error.CommonException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 JWT 필터·HTTP·DB로 관리자 접근 및 허용 정보 투영을 검사한다. 외부 AI·Redis는 호출하지 않는다. */
class AdminHttpTest {
  private static final String SECRET = "admin-http-test-only-secret-at-least-32-bytes";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static String base, schema;
  private static DriverManagerDataSource schemaSource;
  private static boolean postgres;

  @BeforeAll
  static void start() {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    postgres = url != null && !url.isBlank();
    if (postgres && (!url.startsWith("jdbc:postgresql:") || !url.contains("sale_ai_test")))
      throw new IllegalStateException("Dedicated sale_ai_test DB required");
    schema = postgres ? "admin_http_" + Long.toUnsignedString(System.nanoTime()) : "public";
    String user = postgres ? System.getenv("SALE_DRAFT_TEST_DB_USER") : "sa";
    String password = postgres ? System.getenv("SALE_DRAFT_TEST_DB_PASSWORD") : "";
    url =
        postgres
            ? url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema
            : "jdbc:h2:mem:admin_http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    if (postgres) {
      schemaSource = new DriverManagerDataSource(url, user, password);
      Flyway.configure()
          .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
          .dataSource(schemaSource)
          .schemas(schema)
          .defaultSchema(schema)
          .locations("classpath:db/migration")
          .load()
          .migrate();
      Flyway.configure()
          .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
          .dataSource(schemaSource)
          .schemas(schema)
          .defaultSchema(schema)
          .outOfOrder(true)
          .locations(
              "classpath:db/migration",
              "classpath:db/pending/trade-p2",
              "classpath:db/pending/trade-p3")
          .load()
          .migrate();
    }
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    context =
        app.run(
            "--spring.profiles.active=test",
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=" + user,
            "--spring.datasource.password=" + password,
            "--spring.datasource.driver-class-name="
                + (postgres ? "org.postgresql.Driver" : "org.h2.Driver"),
            "--spring.flyway.enabled=" + !postgres,
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--app.features.trade-purchase-requests.enabled=true",
            "--app.features.trade-p3.enabled=" + postgres,
            "--app.features.trade-p4.enabled=" + postgres,
            "--app.features.listing-history.enabled=false",
            "--app.ai.summary.enabled=false",
            "--app.ai.listing.api-key=",
            "--app.jwt.secret=" + SECRET);
    jdbc = context.getBean(JdbcTemplate.class);
    // H2용 조회 fixture: 부분 인덱스가 필요한 정식 P2/P3 DDL은 별도의 PostgreSQL 실행에서 검증한다.
    if (!postgres)
      jdbc.execute(
          """
        CREATE TABLE purchase_requests(id BIGINT PRIMARY KEY, room_id BIGINT, listing_id BIGINT,
          request_status VARCHAR(20), transaction_status VARCHAR(20), cancelled_by_id BIGINT,
          cancelled_at TIMESTAMP WITH TIME ZONE, cancel_reason VARCHAR(500))
        """);
    jdbc.update(
        """
        INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES
        (1,'seller@admin-test.invalid','unused','a-seller','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
        (2,'buyer@admin-test.invalid','unused','a-buyer','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
        (9,'admin@admin-test.invalid','unused','a-admin','ADMIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
        """);
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() {
    if (context != null) context.close();
    if (schemaSource != null && schema != null && schema.matches("admin_http_[0-9]+"))
      new JdbcTemplate(schemaSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
  }

  @BeforeEach
  void fixture() {
    jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = 9");
    jdbc.update("UPDATE users SET nickname = 'a-seller' WHERE id = 1");
    jdbc.update("UPDATE products SET owner_id=1, facts_version=1, archived_at=NULL WHERE id=101");
    if (postgres) jdbc.update("DELETE FROM transaction_change_reviews");
    for (String table :
        List.of(
            "audit_logs",
            "ownership_transfers",
            "purchase_requests",
            "chat_messages",
            "chat_rooms",
            "listings")) jdbc.update("DELETE FROM " + table);
    jdbc.update("DELETE FROM product_events WHERE id >= 9000");
    jdbc.update(
        """
        INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot)
        VALUES(501,101,1,'예약 글',1000,3,'설명','RESERVED',CURRENT_TIMESTAMP,501,0,1),
        (502,102,1,'취소 글',1000,3,'설명','ACTIVE',CURRENT_TIMESTAMP,502,0,1),
        (503,201,2,'완료 글',1000,3,'설명','SOLD',CURRENT_TIMESTAMP,503,0,NULL)
        """);
    jdbc.update(
        "INSERT INTO chat_rooms(id,listing_id,seller_id,inquirer_id) VALUES(11,501,1,2),(12,502,1,2),(13,503,2,1)");
    jdbc.update(
        "INSERT INTO chat_messages(room_id,sender_id,content) VALUES(11,1,'PRIVATE_CHAT_BODY')");
    jdbc.update(
        """
        INSERT INTO purchase_requests(id,room_id,listing_id,request_status,transaction_status,cancelled_by_id,cancelled_at,cancel_reason)
        VALUES(41,11,501,'ACCEPTED','RESERVED',NULL,NULL,NULL),
        (42,12,502,'CANCELLED','CANCELLED',2,TIMESTAMP WITH TIME ZONE '2026-10-29 13:00:00+00','MUTUAL_AGREEMENT'),
        (43,13,503,'ACCEPTED','COMPLETED',NULL,NULL,NULL)
        """);
    audit(
        101,
        101,
        1L,
        41,
        "PURCHASE_REQUEST_ACCEPTED",
        "PENDING",
        "ACCEPTED",
        null,
        "2026-10-28T09:00:00Z");
    audit(
        102,
        102,
        1L,
        42,
        "PURCHASE_REQUEST_ACCEPTED",
        "PENDING",
        "ACCEPTED",
        null,
        "2026-10-29T09:00:00Z");
    audit(
        103,
        201,
        2L,
        43,
        "TRADE_COMPLETED",
        null,
        "{\"transactionStatus\":\"COMPLETED\",\"token\":\"PRIVATE_TRANSFER_TOKEN\"}",
        null,
        "2026-10-29T14:00:00Z");
    audit(
        104,
        101,
        1L,
        77,
        "HISTORY_CORRECTED",
        "{\"title\":\"점검\",\"status\":\"VALID\",\"content\":\"RAM 16GB\",\"objectKey\":\"PRIVATE_OBJECT_KEY\",\"document\":{\"ocr\":\"PRIVATE_OCR\"}}",
        "{\"title\":\"정정 점검\",\"content\":\"RAM 32GB\",\"specsAfter\":\"{\\\"ramGb\\\":32,\\\"serialNumber\\\":\\\"PRIVATE_SERIAL\\\"}\",\"messages\":[\"PRIVATE_CHAT_BODY\"]}",
        "메모리 용량 오기 정정",
        "2026-10-30T09:00:00Z");
    audit(
        105,
        101,
        null,
        77,
        "HISTORY_VOIDED",
        "{\"status\":\"VALID\"}",
        "{\"status\":\"VOID\"}",
        "중복 이력",
        "2026-10-30T09:00:00Z");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/admin",
        "/admin/",
        "/admin.html",
        "/pages/admin.html",
        "/api/v1/admin/trades",
        "/api/v1/admin/trades/41",
        "/api/v1/admin/trades/41/timeline",
        "/api/v1/admin/trades/41/progress",
        "/api/v1/admin/audit-logs",
        "/api/v1/admin/audit-logs/104"
      })
  void protectsEveryEntry(String path) throws Exception {
    assertEquals(401, send(path, null, "GET").statusCode());
    assertEquals(403, send(path, jwt("1", "ADMIN"), "GET").statusCode());
  }

  @Test
  void currentDbRoleOverridesJwtScopeAndRevocationAppliesImmediately() throws Exception {
    String token = jwt("9", "USER");
    assertEquals(200, send("/admin", token, "GET").statusCode());
    jdbc.update("UPDATE users SET role = 'USER' WHERE id = 9");
    assertEquals(403, send("/api/v1/admin/audit-logs", token, "GET").statusCode());
    assertEquals(403, send("/api/v1/admin/trades/41/timeline", token, "GET").statusCode());
    assertEquals(403, send("/api/v1/admin/trades/41/progress", token, "GET").statusCode());
    assertEquals(403, send("/admin.html", token, "GET").statusCode());
    assertEquals(403, send("/api/v1/admin/trades", jwt("99999", "ADMIN"), "GET").statusCode());
    assertEquals(
        403, send("/api/v1/admin/trades", jwt("not-a-number", "ADMIN"), "GET").statusCode());
  }

  @Test
  void servesProtectedHtmlWithCookieAndNoStore() throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(base + "/admin"))
            .header("Cookie", "access_token=" + jwt("9", "ADMIN"))
            .GET()
            .build();
    var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("거래·취소 기록"));
    assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
  }

  @Test
  void pagesAndFiltersTradesWithActualCancellationMetadata() throws Exception {
    var page = data("/api/v1/admin/trades?size=1&page=1");
    assertEquals(3, page.path("total").asInt());
    assertEquals(42, page.path("items").get(0).path("id").asInt());
    var selected =
        data(
            "/api/v1/admin/trades?status=CANCELLED&productId=102&actorId=2&from=2026-10-29&to=2026-10-29");
    assertEquals(1, selected.path("total").asInt());
    var detail = data("/api/v1/admin/trades/42");
    assertEquals("MUTUAL_AGREEMENT", detail.path("cancelReason").asText());
    assertEquals(2, detail.path("cancelledById").asInt());
    assertEquals("2026-10-29T13:00:00Z", detail.path("cancelledAt").asText());
    assertEquals("2026-10-29T09:00:00Z", detail.path("reservedAt").asText());
    assertTrue(detail.path("completedAt").isNull());
    assertEquals(
        "2026-10-29T14:00:00Z", data("/api/v1/admin/trades/43").path("completedAt").asText());
    assertFalse(detail.toString().contains("PRIVATE_"));
    assertFalse(detail.has("chatMessages"));
  }

  @Test
  void overviewUsesWholeSearchScopeWhileListUsesSelectedStatusAndReason() throws Exception {
    var all = data("/api/v1/admin/trades?size=1");
    var overview = all.path("overview");
    assertEquals(3, overview.path("total").asLong());
    assertEquals(1, overview.path("reserved").asLong());
    assertEquals(1, overview.path("completed").asLong());
    assertEquals(1, overview.path("cancelled").asLong());
    assertEquals(0, overview.path("pending").asLong());
    assertEquals(6, overview.path("cancellations").size());
    assertEquals(1, cancellationCount(overview, "MUTUAL_AGREEMENT"));
    for (String query :
        List.of(
            "status=RESERVED",
            "status=CANCELLED",
            "cancelReason=MUTUAL_AGREEMENT",
            "status=CANCELLED&cancelReason=MUTUAL_AGREEMENT")) {
      var selected = data("/api/v1/admin/trades?" + query);
      assertEquals(1, selected.path("total").asLong());
      assertEquals(overview, selected.path("overview"));
    }
    assertEquals(overview, data("/api/v1/admin/trades?page=99").path("overview"));
    var noMatchingReason = data("/api/v1/admin/trades?cancelReason=SELF_CIRCUMSTANCES");
    assertEquals(0, noMatchingReason.path("total").asLong());
    assertEquals(overview, noMatchingReason.path("overview"));
  }

  @Test
  void overviewSharesProductMemberAndReservationDateConditionsWithTheList() throws Exception {
    var product = data("/api/v1/admin/trades?productQuery=macbook&memberQuery=A-BUYER");
    assertEquals(1, product.path("overview").path("total").asLong());
    assertEquals(1, product.path("overview").path("reserved").asLong());
    var period = data("/api/v1/admin/trades?from=2026-10-29&to=2026-10-29");
    assertEquals(1, period.path("overview").path("total").asLong());
    assertEquals(1, period.path("overview").path("cancelled").asLong());
    // 같은 날 완료한 43도 예약 감사 시각이 없으므로 예약일을 지정한 범위에는 포함하지 않는다.
    assertEquals(0, period.path("overview").path("completed").asLong());
    var missing = data("/api/v1/admin/trades?productId=999").path("overview");
    assertEquals(0, missing.path("total").asLong());
    assertEquals(0, missing.path("reserved").asLong());
    assertTrue(
        java.util.stream.StreamSupport.stream(missing.path("cancellations").spliterator(), false)
            .allMatch(item -> item.path("count").asLong() == 0));
  }

  @Test
  void overviewSeparatesPreReservationWithdrawalAndCountsEveryCancellationExactlyOnce()
      throws Exception {
    jdbc.update(
        """
        INSERT INTO purchase_requests(id,room_id,listing_id,request_status,transaction_status)
        VALUES(44,11,501,'CANCELLED',NULL),(45,11,501,'REJECTED',NULL),(46,12,502,'PENDING',NULL)
        """);
    var result = data("/api/v1/admin/trades");
    var overview = result.path("overview");
    assertEquals(6, overview.path("total").asLong());
    assertEquals(1, overview.path("pending").asLong());
    assertEquals(1, overview.path("rejected").asLong());
    assertEquals(2, overview.path("cancelled").asLong());
    assertEquals(1, cancellationCount(overview, "BEFORE_RESERVATION"));
    long sum =
        java.util.stream.StreamSupport.stream(overview.path("cancellations").spliterator(), false)
            .mapToLong(item -> item.path("count").asLong())
            .sum();
    assertEquals(overview.path("cancelled").asLong(), sum);
    long stateSum = 0;
    for (String key : List.of("pending", "rejected", "reserved", "completed", "cancelled", "other"))
      stateSum += overview.path(key).asLong();
    assertEquals(overview.path("total").asLong(), stateSum);
    var selected = data("/api/v1/admin/trades?cancelReason=BEFORE_RESERVATION");
    assertEquals(1, selected.path("total").asLong());
    assertEquals(44, selected.path("items").get(0).path("id").asLong());
    assertEquals(overview, selected.path("overview"));
  }

  @Test
  void overviewKeepsLegacyUnknownStatusAndCancellationReasonWithoutInventingTheirMeaning()
      throws Exception {
    Assumptions.assumeFalse(postgres, "정식 P3 제약 전의 레거시 입력은 H2 조회 fixture에서 검증");
    jdbc.update("UPDATE purchase_requests SET cancel_reason='PRIVATE_LEGACY_REASON' WHERE id=42");
    jdbc.update(
        "INSERT INTO purchase_requests(id,room_id,listing_id,request_status) VALUES(47,11,501,'ACCEPTED')");
    var result = data("/api/v1/admin/trades?cancelReason=UNRECORDED");
    assertEquals(1, result.path("total").asLong());
    assertEquals(1, result.path("overview").path("other").asLong());
    assertEquals(1, cancellationCount(result.path("overview"), "UNRECORDED"));
    assertFalse(result.path("overview").toString().contains("PRIVATE_"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "cancelReason=unknown",
        "cancelReason=MUTUAL_AGREEMENT%27",
        "status=RESERVED&cancelReason=MUTUAL_AGREEMENT"
      })
  void rejectsUnsupportedCancellationFilters(String query) throws Exception {
    assertEquals(400, send("/api/v1/admin/trades?" + query, jwt("9", "ADMIN"), "GET").statusCode());
  }

  @ParameterizedTest
  @CsvSource({
    "2026-10-29T14:59:59.999999Z,false",
    "2026-10-29T15:00:00Z,true",
    "2026-10-30T14:59:59.999999Z,true",
    "2026-10-30T15:00:00Z,false"
  })
  void koreanCalendarBoundariesMatchTradeListOverviewAndAuditList(String at, boolean included)
      throws Exception {
    jdbc.update(
        "UPDATE audit_logs SET created_at=? WHERE id=101", java.time.OffsetDateTime.parse(at));
    audit(610, 101, 1L, 101, "PRODUCT_INFO_CORRECTED", null, null, "검색 경계 시험", at);
    String dates = "productId=101&from=2026-10-30&to=2026-10-30";
    var trades = data("/api/v1/admin/trades?" + dates);
    var audits = data("/api/v1/admin/audit-logs?action=PRODUCT_INFO_CORRECTED&" + dates);
    long expected = included ? 1 : 0;
    assertEquals(expected, trades.path("total").asLong());
    assertEquals(expected, trades.path("overview").path("total").asLong());
    assertEquals(expected, trades.path("overview").path("reserved").asLong());
    assertEquals(expected, audits.path("total").asLong());
    if (included) {
      assertEquals(
          java.time.Instant.parse(at),
          java.time.Instant.parse(trades.path("items").get(0).path("reservedAt").asText()));
      assertEquals(
          java.time.Instant.parse(at),
          java.time.Instant.parse(audits.path("items").get(0).path("createdAt").asText()));
    }
  }

  @Test
  void koreanDateSearchSupportsSingleSidedRangesAndPagesWithoutChangingStoredTimestamps()
      throws Exception {
    String[] times = {
      "2026-10-29T14:59:59.999999Z",
      "2026-10-29T15:00:00Z",
      "2026-10-30T14:59:59.999999Z",
      "2026-10-30T15:00:00Z"
    };
    for (int i = 0; i < times.length; i++)
      audit(610 + i, 101, 1L, 101, "PRODUCT_INFO_CORRECTED", null, null, null, times[i]);
    String query = "/api/v1/admin/audit-logs?action=PRODUCT_INFO_CORRECTED&productId=101";
    assertEquals(3, data(query + "&from=2026-10-30").path("total").asLong());
    assertEquals(3, data(query + "&to=2026-10-30").path("total").asLong());
    assertEquals(1, data(query + "&from=2026-10-29&to=2026-10-29").path("total").asLong());
    var page = data(query + "&from=2026-10-30&to=2026-10-30&size=1&page=1");
    assertEquals(2, page.path("total").asLong());
    assertEquals(611, page.path("items").get(0).path("id").asLong());
    assertEquals("2026-10-29T15:00:00Z", page.path("items").get(0).path("createdAt").asText());
  }

  private long cancellationCount(JsonNode overview, String category) {
    return java.util.stream.StreamSupport.stream(
            overview.path("cancellations").spliterator(), false)
        .filter(item -> item.path("category").asText().equals(category))
        .findFirst()
        .orElseThrow()
        .path("count")
        .asLong();
  }

  @Test
  void auditsShowChangesAndReasonWithoutRawPrivateFields() throws Exception {
    var response = send("/api/v1/admin/audit-logs/104", jwt("9", "ADMIN"), "GET");
    assertEquals(200, response.statusCode());
    assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    assertFalse(response.body().contains("PRIVATE_"));
    assertFalse(response.body().contains("objectKey"));
    var value = JSON.readTree(response.body()).path("data");
    assertEquals("RAM 16GB", value.path("before").path("values").path("content").asText());
    assertEquals(32, value.path("after").path("values").path("specsAfter").path("ramGb").asInt());
    assertEquals("메모리 용량 오기 정정", value.path("reason").asText());
    var logs = data("/api/v1/admin/audit-logs?productId=101&from=2026-10-30&to=2026-10-30&size=1");
    assertEquals(2, logs.path("total").asInt());
    assertEquals(105, logs.path("items").get(0).path("id").asInt());
    assertTrue(logs.path("items").get(0).path("actorId").isNull());
    assertEquals(
        1,
        data("/api/v1/admin/audit-logs?action=HISTORY_CORRECTED&actorId=1").path("total").asInt());
    assertEquals(
        "PENDING",
        data("/api/v1/admin/audit-logs/101").path("before").path("values").path("status").asText());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "size=101",
        "size=0",
        "page=-1",
        "page=100001",
        "productId=0",
        "actorId=-2",
        "from=2026-11-01&to=2026-10-30",
        "from=not-a-date",
        "action=HISTORY_VOIDED%27",
        "status=UNKNOWN"
      })
  void rejectsInvalidFilters(String query) throws Exception {
    assertEquals(
        400, send("/api/v1/admin/audit-logs?" + query, jwt("9", "ADMIN"), "GET").statusCode());
  }

  @Test
  void detailNotFoundAndEmptyPagesAreHonest() throws Exception {
    assertEquals(404, send("/api/v1/admin/trades/999", jwt("9", "ADMIN"), "GET").statusCode());
    assertEquals(
        404, send("/api/v1/admin/trades/999/timeline", jwt("9", "ADMIN"), "GET").statusCode());
    assertEquals(
        404, send("/api/v1/admin/trades/999/progress", jwt("9", "ADMIN"), "GET").statusCode());
    assertEquals(404, send("/api/v1/admin/audit-logs/999", jwt("9", "ADMIN"), "GET").statusCode());
    assertEquals(0, data("/api/v1/admin/trades?productId=999").path("total").asInt());
    var page = data("/api/v1/admin/audit-logs?page=100");
    assertEquals(5, page.path("total").asInt());
    assertTrue(page.path("items").isEmpty());
  }

  @Test
  void adminRoutesHaveNoMutationEndpointsAndReadsDoNotChangeBusinessState() throws Exception {
    String before = jdbc.queryForList("SELECT * FROM purchase_requests ORDER BY id").toString();
    for (String method : List.of("POST", "PATCH", "DELETE"))
      assertEquals(405, send("/api/v1/admin/trades/41", jwt("9", "ADMIN"), method).statusCode());
    data("/api/v1/admin/trades");
    data("/api/v1/admin/trades/41");
    data("/api/v1/admin/trades/41/timeline");
    data("/api/v1/admin/trades/41/progress");
    data("/api/v1/admin/audit-logs");
    data("/api/v1/admin/audit-logs/104");
    assertEquals(
        before, jdbc.queryForList("SELECT * FROM purchase_requests ORDER BY id").toString());
    assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM chat_messages WHERE content='PRIVATE_CHAT_BODY'", Integer.class));
  }

  @Test
  void disabledTradeFeatureDoesNotAttemptMissingTables() {
    var service =
        new AdminReadService(
            context.getBean(AdminAccessService.class),
            context.getBean(AdminReadRepository.class),
            context.getBean(AdminAuditFacts.class),
            false);
    assertEquals(
        "ADMIN_001",
        assertThrows(CommonException.class, () -> service.trade(9, 41)).errorCode().code());
  }

  @Test
  void joinsCurrentProductAndMemberInformationWithoutCredentialsOrSerialData() throws Exception {
    var trade = data("/api/v1/admin/trades/41");
    assertEquals("MacBook Pro 14 M3 Pro", trade.path("product").path("title").asText());
    assertEquals("Apple", trade.path("product").path("manufacturer").asText());
    assertEquals("MacBook Pro 14", trade.path("product").path("modelName").asText());
    assertEquals("a-seller", trade.path("seller").path("nickname").asText());
    assertEquals("buyer@admin-test.invalid", trade.path("buyer").path("email").asText());
    assertEquals("예약 글", trade.path("listingTitle").asText());
    var cancelled = data("/api/v1/admin/trades/42");
    assertEquals("a-buyer", cancelled.path("cancelledBy").path("nickname").asText());
    var logs = data("/api/v1/admin/audit-logs/104").path("record");
    assertEquals("a-seller", logs.path("actor").path("nickname").asText());
    assertEquals("seller@admin-test.invalid", logs.path("actor").path("email").asText());
    assertEquals("MacBook Pro 14 M3 Pro", logs.path("product").path("title").asText());
    String payload = trade.toString() + logs;
    for (String forbidden :
        List.of(
            "passwordHash",
            "password_hash",
            "serial_number",
            "serialNumber",
            "test-ciphertext",
            "PRIVATE_")) assertFalse(payload.contains(forbidden));
    jdbc.update("UPDATE users SET nickname='a-renamed' WHERE id=1");
    assertEquals(
        "a-renamed", data("/api/v1/admin/trades/41").path("seller").path("nickname").asText());
  }

  @Test
  void searchesNamesModelsNicknamesAndEmailsAsLiteralCaseInsensitiveText() throws Exception {
    assertEquals(
        1,
        data("/api/v1/admin/trades?productQuery=macbook&memberQuery=A-BUYER")
            .path("total")
            .asInt());
    assertEquals(1, data("/api/v1/admin/trades?productQuery=LENOVO").path("total").asInt());
    assertEquals(
        3,
        data("/api/v1/admin/audit-logs?memberQuery=seller%40admin-test.invalid")
            .path("total")
            .asInt());
    for (String literal : List.of("%", "_", "' OR 1=1 --", "missing-product")) {
      String query = java.net.URLEncoder.encode(literal, StandardCharsets.UTF_8);
      assertEquals(0, data("/api/v1/admin/trades?productQuery=" + query).path("total").asInt());
      assertEquals(0, data("/api/v1/admin/audit-logs?memberQuery=" + query).path("total").asInt());
    }
  }

  @Test
  void missingActorDoesNotDropRecordsOrTurnThemIntoSystemActions() throws Exception {
    jdbc.update("UPDATE audit_logs SET actor_id=99999 WHERE id=104");
    var record = data("/api/v1/admin/audit-logs/104").path("record");
    assertEquals(99999, record.path("actorId").asInt());
    assertEquals(99999, record.path("actor").path("id").asInt());
    assertTrue(record.path("actor").path("nickname").isNull());
    assertTrue(data("/api/v1/admin/audit-logs/105").path("record").path("actor").isNull());
    assertEquals(5, data("/api/v1/admin/audit-logs").path("total").asInt());
  }

  @Test
  void timelineUsesActionAndRealTransferConnectionRatherThanCollidingTargetIds() throws Exception {
    jdbc.update(
        "UPDATE listings SET published_at = TIMESTAMP WITH TIME ZONE '2026-10-25 09:00:00+00' WHERE id=501");
    transfer(71, 101, 41, "INTERNAL");
    transfer(41, 101, 42, "INTERNAL");
    transfer(72, 101, 41, "DIRECT");
    transfer(73, 102, 41, "INTERNAL");
    audit(
        201,
        101,
        1L,
        41,
        "HISTORY_CORRECTED",
        null,
        null,
        "UNRELATED_HISTORY",
        "2026-10-28T09:01:00Z");
    audit(
        202,
        102,
        1L,
        41,
        "TRADE_COMPLETED",
        null,
        null,
        "UNRELATED_PRODUCT",
        "2026-10-28T09:01:00Z");
    audit(
        203,
        101,
        1L,
        41,
        "OWNERSHIP_TRANSFERRED",
        null,
        null,
        "UNRELATED_REQUEST",
        "2026-10-28T09:01:00Z");
    audit(
        204,
        101,
        1L,
        72,
        "OWNERSHIP_TRANSFERRED",
        null,
        null,
        "UNRELATED_DIRECT",
        "2026-10-28T09:01:00Z");
    audit(
        205,
        101,
        1L,
        71,
        "OWNERSHIP_TRANSFER_CREATED",
        "PRIVATE_TRANSFER_TOKEN",
        "PRIVATE_OCR",
        "내부 이전 준비",
        "2026-10-28T09:02:00Z");
    var timeline = data("/api/v1/admin/trades/41/timeline");
    var events = timeline.path("events");
    assertEquals(4, events.path("total").asInt());
    assertEquals("LISTING_PUBLISHED", events.path("items").get(0).path("action").asText());
    assertEquals(101, events.path("items").get(1).path("auditId").asInt());
    assertEquals(205, events.path("items").get(2).path("auditId").asInt());
    assertEquals("OWNERSHIP_TRANSFERRED", events.path("items").get(3).path("action").asText());
    assertEquals("UNRECORDED", events.path("items").get(3).path("actorType").asText());
    assertFalse(timeline.toString().contains("PRIVATE_"));
    assertFalse(timeline.toString().contains("UNRELATED_"));
    assertFalse(timeline.toString().contains("tokenHash"));
    assertEquals(postgres, timeline.path("reviewHistoryAvailable").asBoolean());
    var productRecords = data("/api/v1/admin/audit-logs?productId=101");
    assertTrue(productRecords.toString().contains("HISTORY_CORRECTED"));
    assertFalse(productRecords.toString().contains("ThinkPad"));
  }

  @Test
  void timelinePagesStableTiesAndKeepsUnknownMembersSeparateFromSystem() throws Exception {
    jdbc.update(
        "UPDATE listings SET published_at = TIMESTAMP WITH TIME ZONE '2026-10-25 09:00:00+00' WHERE id=501");
    audit(210, 101, null, 41, "TRADE_COMPLETED", null, null, null, "2026-10-28T09:00:00Z");
    audit(211, 101, 99999L, 41, "TRADE_COMPLETED", null, null, null, "2026-10-28T09:00:00Z");
    var first = data("/api/v1/admin/trades/41/timeline?size=2").path("events");
    var next = data("/api/v1/admin/trades/41/timeline?size=2&page=1").path("events");
    assertEquals(4, first.path("total").asInt());
    assertEquals(101, first.path("items").get(1).path("auditId").asInt());
    assertEquals(210, next.path("items").get(0).path("auditId").asInt());
    assertEquals("SYSTEM", next.path("items").get(0).path("actorType").asText());
    var missing = next.path("items").get(1);
    assertEquals("MEMBER", missing.path("actorType").asText());
    assertEquals(99999, missing.path("actor").path("id").asLong());
    assertTrue(missing.path("actor").path("nickname").isNull());
    assertEquals(next, data("/api/v1/admin/trades/41/timeline?size=2&page=1").path("events"));
    assertTrue(
        data("/api/v1/admin/trades/41/timeline?page=99").path("events").path("items").isEmpty());
  }

  @Test
  void timelineCancellationFallbackDoesNotDuplicateAuditAndDoesNotInventRequestTime()
      throws Exception {
    var before = data("/api/v1/admin/trades/42/timeline").path("events");
    assertEquals(3, before.path("total").asInt());
    assertTrue(before.toString().contains("TRANSACTION:42:TRADE_RESERVATION_CANCELLED"));
    audit(
        212,
        102,
        2L,
        42,
        "TRADE_RESERVATION_CANCELLED",
        null,
        null,
        "MUTUAL_AGREEMENT",
        "2026-10-29T13:00:00Z");
    var after = data("/api/v1/admin/trades/42/timeline").path("events");
    assertEquals(3, after.path("total").asInt());
    assertFalse(after.toString().contains("TRANSACTION:42:"));
    assertTrue(after.toString().contains("AUDIT:212:"));
    jdbc.update("DELETE FROM audit_logs WHERE product_id=101");
    var pending = data("/api/v1/admin/trades/41/timeline").path("events");
    assertEquals(1, pending.path("total").asInt());
    assertEquals("LISTING_PUBLISHED", pending.path("items").get(0).path("action").asText());
  }

  @Test
  void timelineP3ShowsSavedReviewTimesAndBuyerDecisionWithoutChangeFacts() throws Exception {
    Assumptions.assumeTrue(postgres, "P3 정식 DDL은 PostgreSQL에서 검증");
    jdbc.update(
        "INSERT INTO product_events(id,product_id,author_id,title) VALUES(9001,101,1,'비공개 점검'),(9002,102,1,'다른 제품 점검')");
    jdbc.update(
        """
        INSERT INTO transaction_change_reviews(purchase_request_id,event_id,facts_version,change_facts,decision,created_at,reviewed_at)
        VALUES(41,9001,1,'{"content":"PRIVATE_CHANGE_FACTS"}','KEEP',TIMESTAMP WITH TIME ZONE '2026-10-28 11:00:00+00',TIMESTAMP WITH TIME ZONE '2026-10-28 12:00:00+00'),
        (42,9002,1,'{}','CANCEL',TIMESTAMP WITH TIME ZONE '2026-10-28 11:00:00+00',TIMESTAMP WITH TIME ZONE '2026-10-28 12:00:00+00')
        """);
    var response = data("/api/v1/admin/trades/41/timeline");
    var items = response.path("events").path("items");
    var recorded =
        java.util.stream.StreamSupport.stream(items.spliterator(), false)
            .filter(item -> item.path("action").asText().equals("RESERVATION_CHANGE_RECORDED"))
            .findFirst()
            .orElseThrow();
    var reviewed =
        java.util.stream.StreamSupport.stream(items.spliterator(), false)
            .filter(item -> item.path("action").asText().equals("RESERVATION_CHANGE_KEPT"))
            .findFirst()
            .orElseThrow();
    assertEquals("SYSTEM", recorded.path("actorType").asText());
    assertEquals("2026-10-28T11:00:00Z", recorded.path("occurredAt").asText());
    assertEquals(2, reviewed.path("actor").path("id").asInt());
    assertEquals("2026-10-28T12:00:00Z", reviewed.path("occurredAt").asText());
    assertFalse(response.toString().contains("PRIVATE_"));
    assertFalse(response.toString().contains("9002"));
    assertFalse(response.toString().contains("changeFacts"));
    assertTrue(response.path("reviewHistoryAvailable").asBoolean());
  }

  @ParameterizedTest
  @ValueSource(strings = {"page=-1", "page=100001", "size=0", "size=101", "size=no"})
  void timelineRejectsInvalidPage(String query) throws Exception {
    assertEquals(
        400,
        send("/api/v1/admin/trades/41/timeline?" + query, jwt("9", "ADMIN"), "GET").statusCode());
  }

  @Test
  void progressDoesNotReopenEndedTransactionsAndMissingFeatureIsNotZeroPending() throws Exception {
    for (var entry : Map.of(42, "CANCELLED", 43, "COMPLETED").entrySet()) {
      var result = data("/api/v1/admin/trades/" + entry.getKey() + "/progress");
      assertEquals(entry.getValue(), result.path("state").asText());
      assertEquals("NOT_APPLICABLE", result.path("sellerConfirmation").asText());
      assertTrue(result.path("factsVersion").isNull());
      assertTrue(result.path("unreviewedChangeCount").isNull());
    }
    if (!postgres) {
      // 이 환경에는 확인 열·P3 테이블이 없다. 제공되지 않는 상태를 미확인 0건으로 추정하지 않는다.
      var result = data("/api/v1/admin/trades/41/progress");
      assertEquals("CONFIRMATION_UNAVAILABLE", result.path("state").asText());
      assertTrue(result.path("unreviewedChangeCount").isNull());
    }
  }

  @Test
  void progressUsesCurrentConfirmationVersionAndOnlyThisRequestsUnreviewedCount() throws Exception {
    Assumptions.assumeTrue(postgres, "확인 상태와 P3 정식 DDL은 PostgreSQL에서 검증");
    String path = "/api/v1/admin/trades/41/progress";
    assertEquals("WAITING_BOTH_CONFIRMATIONS", data(path).path("state").asText());
    jdbc.update("UPDATE purchase_requests SET seller_confirmed_version=1 WHERE id=41");
    assertEquals("WAITING_BUYER_CONFIRMATION", data(path).path("state").asText());
    jdbc.update(
        "UPDATE purchase_requests SET seller_confirmed_version=NULL, buyer_confirmed_version=1 WHERE id=41");
    assertEquals("WAITING_SELLER_CONFIRMATION", data(path).path("state").asText());
    jdbc.update("UPDATE purchase_requests SET seller_confirmed_version=1 WHERE id=41");
    assertEquals("CONFIRMATIONS_RECEIVED", data(path).path("state").asText());
    jdbc.update(
        "INSERT INTO product_events(id,product_id,author_id,title) VALUES(9001,101,1,'점검'),(9002,102,1,'다른 제품 점검')");
    jdbc.update(
        """
        INSERT INTO transaction_change_reviews(purchase_request_id,event_id,facts_version,change_facts)
        VALUES(41,9001,1,'{"content":"PRIVATE_CHANGE_FACTS"}'),(42,9002,1,'{}')
        """);
    var pending = data(path);
    assertEquals("WAITING_BUYER_CHANGE_REVIEW", pending.path("state").asText());
    assertEquals(1, pending.path("unreviewedChangeCount").asLong());
    assertEquals("CURRENT", pending.path("sellerConfirmation").asText());
    assertFalse(pending.toString().contains("PRIVATE_"));
    assertFalse(pending.has("changeFacts"));
    jdbc.update(
        "UPDATE transaction_change_reviews SET decision='KEEP',reviewed_at=CURRENT_TIMESTAMP WHERE purchase_request_id=41");
    assertEquals(0, data(path).path("unreviewedChangeCount").asLong());
    assertEquals("CONFIRMATIONS_RECEIVED", data(path).path("state").asText());
    jdbc.update("UPDATE products SET facts_version=2 WHERE id=101");
    var changed = data(path);
    assertEquals("WAITING_BOTH_CONFIRMATIONS", changed.path("state").asText());
    assertEquals("RECONFIRM_REQUIRED", changed.path("sellerConfirmation").asText());
    assertEquals("RECONFIRM_REQUIRED", changed.path("buyerConfirmation").asText());
    assertEquals(2, changed.path("factsVersion").asLong());
    assertEquals(1, changed.path("sellerConfirmedVersion").asLong());
    assertFalse(changed.has("completedAt"));
    assertFalse(changed.has("completionAllowed"));
  }

  @Test
  void inconsistentReservedConnectionIsNotBlamedOnMissingPartyConfirmation() throws Exception {
    Assumptions.assumeTrue(postgres, "확인 상태 투영 SQL은 PostgreSQL에서 검증");
    jdbc.update("UPDATE products SET owner_id=2 WHERE id=101");
    var result = data("/api/v1/admin/trades/41/progress");
    assertEquals("CONNECTION_CHECK_REQUIRED", result.path("state").asText());
    assertEquals("UNAVAILABLE", result.path("sellerConfirmation").asText());
    assertTrue(result.path("unreviewedChangeCount").isNull());
  }

  private void transfer(long id, long product, long request, String type) {
    jdbc.update(
        """
        INSERT INTO ownership_transfers(id,product_id,from_user_id,to_user_id,token_hash,status,transfer_type,purchase_request_id,expires_at,created_at,completed_at)
        VALUES(?,?,1,2,?,'COMPLETED',?,?,TIMESTAMP WITH TIME ZONE '2026-10-29 09:00:00+00',TIMESTAMP WITH TIME ZONE '2026-10-28 09:01:00+00',TIMESTAMP WITH TIME ZONE '2026-10-28 10:00:00+00')
        """,
        id,
        product,
        "PRIVATE_TOKEN_HASH_" + id,
        type,
        request);
  }

  private void audit(
      long id,
      long product,
      Long actor,
      long target,
      String action,
      String before,
      String after,
      String reason,
      String at) {
    jdbc.update(
        "INSERT INTO audit_logs(id,product_id,actor_id,target_id,action,before_facts,after_facts,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        product,
        actor,
        target,
        action,
        before,
        after,
        reason,
        java.time.OffsetDateTime.parse(at));
  }

  private JsonNode data(String path) throws Exception {
    var response = send(path, jwt("9", "ADMIN"), "GET");
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body()).path("data");
  }

  private HttpResponse<String> send(String path, String token, String method) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create(base + path));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    return HTTP.send(
        builder.method(method, HttpRequest.BodyPublishers.noBody()).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String jwt(String subject, String scope) throws Exception {
    var b64 = Base64.getUrlEncoder().withoutPadding();
    String header = b64.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
    String body =
        b64.encodeToString(
            JSON.writeValueAsBytes(
                Map.of(
                    "sub",
                    subject,
                    "scope",
                    scope,
                    "iat",
                    Instant.now().getEpochSecond(),
                    "exp",
                    Instant.now().plusSeconds(300).getEpochSecond())));
    String signing = header + "." + body;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return signing
        + "."
        + b64.encodeToString(mac.doFinal(signing.getBytes(StandardCharsets.UTF_8)));
  }
}
