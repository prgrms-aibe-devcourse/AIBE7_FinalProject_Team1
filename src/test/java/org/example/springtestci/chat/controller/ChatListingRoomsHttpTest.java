package org.example.springtestci.chat.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 HTTP·JWT·DB·운영 JPA 목록과 메시지를 검증한다. 상세의 외부 이력 포트만 기존 READY 시험 대역을 쓴다. PostgreSQL은 이번 작업 전용 루프백
 * DB만 허용하고 브라우저는 명시적으로 지정한 Node 실행기로만 실행한다.
 */
class ChatListingRoomsHttpTest {
  private static final String SECRET = "chat-list-test-signing-secret-at-least-32-bytes";
  private static final String PASSWORD = "ChatList-Test-2026!";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static String baseUrl;
  private static String schema;

  @TestConfiguration(proxyBeanMethods = false)
  static class HistoryFixture {
    @Bean
    @org.springframework.context.annotation.Primary
    org.example.springtestci.chat.repository.ChatRoomLeaveRepository chatLeaveFixture() {
      return new org.example.springtestci.chat.InMemoryChatRoomLeaveRepository();
    }

    @Bean
    ListingHistoryReader chatListHistoryFixture() {
      return productId ->
          new ListingHistoryReader.Snapshot(ListingHistoryReader.State.READY, false, List.of());
    }
  }

  @BeforeAll
  static void start() {
    String url = System.getenv("CHAT_LIST_TEST_DB_URL");
    boolean postgres = url != null && !url.isBlank();
    if (postgres && !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55461/chat_list_test"))
      throw new IllegalStateException("Use the dedicated chat-list loopback database only");
    schema = postgres ? "chat_list_" + Long.toUnsignedString(System.nanoTime()) : "public";
    url =
        postgres
            ? url + "?currentSchema=" + schema
            : "jdbc:h2:mem:chat_list;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    var app = new SpringApplication(SpringTestCiApplication.class, HistoryFixture.class);
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
            "--spring.datasource.username=" + (postgres ? "chat_list_test" : "sa"),
            "--spring.datasource.password=" + (postgres ? "chat-list-disposable-only" : ""),
            "--spring.flyway.enabled=true",
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--spring.data.redis.host=127.0.0.1",
            "--spring.data.redis.port=" + (postgres ? "56411" : "1"),
            "--app.features.chat-api.enabled=true",
            "--app.jwt.secret=" + SECRET);
    jdbc = context.getBean(JdbcTemplate.class);
    String hash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
    for (long id : List.of(1L, 2L, 3L, 4L)) user(id, hash);
    for (long id = 100; id < 155; id++) user(id, hash);
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    jdbc.update(
        "INSERT INTO products(id,owner_id,category,title,manufacturer,manufacturer_normalized,model_name,serial_number_fingerprint,serial_number_ciphertext) VALUES(104,1,'LAPTOP','empty','Apple','APPLE','Test','chat-104','test-ciphertext-104')");
    listing(61, 101, "ACTIVE");
    listing(62, 102, "ACTIVE");
    listing(63, 101, "SOLD");
    listing(64, 104, "ACTIVE");
    room(10001, 61, 2);
    room(10002, 61, 3);
    room(20001, 62, 2);
    room(30001, 63, 2);
    for (long id = 100; id < 155; id++) room(10100 + id, 61, id);
    baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  private static void user(long id, String hash) {
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,email_verified,created_at,updated_at) VALUES(?,?,?,?,'USER',TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        id,
        "chat" + id + "@fixture.invalid",
        hash,
        id == 2 ? "<b>buyer</b>" : "chat" + id);
  }

  private static void listing(long id, long productId, String status) {
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES(?,?,1,?,100000,4,'chat list fixture',?,CURRENT_TIMESTAMP,?,0,?)",
        id,
        productId,
        "Listing " + id + " <b>test</b>",
        status,
        id,
        status.equals("ACTIVE") ? 1 : null);
  }

  private static void room(long id, long listingId, long inquirerId) {
    jdbc.update(
        "INSERT INTO chat_rooms(id,listing_id,seller_id,inquirer_id,created_at) VALUES(?,?,1,?,TIMESTAMP '2026-10-01 00:00:00')",
        id,
        listingId,
        inquirerId);
  }

  @BeforeEach
  void resetMessages() {
    jdbc.update("DELETE FROM chat_read_positions");
    jdbc.update("DELETE FROM chat_messages");
    jdbc.update("DELETE FROM chat_rooms WHERE id < 10000");
  }

  @AfterAll
  static void stop() {
    if (context != null) {
      if (!schema.equals("public")) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      context.close();
    }
  }

  @Test
  void filtersBothParticipantRolesWithAccurateCountsAndStablePages() throws Exception {
    JsonNode all = get("/chat-rooms?page=0&size=20", 1, 200).get("data");
    assertEquals(59, all.get("page").get("totalElements").asInt());
    assertEquals(30001, all.get("items").get(0).get("roomId").asLong());
    JsonNode first = get("/chat-rooms?listingId=61&size=20", 1, 200).get("data");
    assertEquals(57, first.get("page").get("totalElements").asInt());
    assertEquals(20, first.get("items").size());
    assertTrue(first.get("page").get("hasNext").asBoolean());
    JsonNode last = get("/chat-rooms?listingId=61&page=2&size=20", 1, 200).get("data");
    assertEquals(17, last.get("items").size());
    assertEquals(10001, last.get("items").get(16).get("roomId").asLong());
    assertFalse(last.get("page").get("hasNext").asBoolean());
    assertEquals(1, get("/chat-rooms?listingId=61", 2, 200).get("data").get("items").size());
    assertEquals(1, get("/chat-rooms?listingId=62", 1, 200).get("data").get("items").size());
    for (long actor : List.of(4L, 999L)) {
      var empty = get("/chat-rooms?listingId=61", actor, 200).get("data");
      assertEquals(0, empty.get("items").size());
      assertEquals(0, empty.get("page").get("totalElements").asInt());
    }
    assertEquals(0, get("/chat-rooms?listingId=999", 1, 200).get("data").get("items").size());
    get("/chat-rooms?listingId=0", 1, 400);
    get("/chat-rooms?listingId=-1", 1, 400);
    get("/chat-rooms?size=101", 1, 400);
    get("/chat-rooms?page=-1", 1, 400);
    var unauthenticated =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/chat-rooms")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, unauthenticated.statusCode());
  }

  @Test
  void preservesOriginalParticipantsAfterOwnershipChangeAndClosedListing() throws Exception {
    jdbc.update("UPDATE products SET owner_id=4 WHERE id=101");
    try {
      assertEquals(0, get("/chat-rooms?listingId=61", 4, 200).get("data").get("items").size());
      get("/chat-rooms/10001/messages", 4, 403);
      send("/chat-rooms/10001/messages", 4, "POST", "{\"content\":\"denied\"}", 403);
      send("/chat-rooms/30001/messages", 2, "POST", "{\"content\":\"closed room reply\"}", 200);
      var messages = get("/chat-rooms/30001/messages", 1, 200).get("data").get("items");
      long messageId = messages.get(0).get("messageId").asLong();
      assertTrue(
          get("/chat-rooms?listingId=63", 1, 200)
              .get("data")
              .get("items")
              .get(0)
              .get("hasUnreadMessages")
              .asBoolean());
      send(
          "/chat-rooms/30001/read-position",
          1,
          "PUT",
          "{\"lastReadMessageId\":" + messageId + "}",
          200);
      assertFalse(
          get("/chat-rooms?listingId=63", 1, 200)
              .get("data")
              .get("items")
              .get(0)
              .get("hasUnreadMessages")
              .asBoolean());
    } finally {
      jdbc.update("UPDATE products SET owner_id=1 WHERE id=101");
    }
  }

  @Test
  void usesRecentMessageBeforeCreationTimeAndDoesNotReadByListing() throws Exception {
    send(
        "/chat-rooms/10001/messages",
        2,
        "POST",
        "{\"content\":\"<img src=x onerror=alert(1)>\"}",
        200);
    var first = get("/chat-rooms?listingId=61", 1, 200).get("data").get("items").get(0);
    assertEquals(10001, first.get("roomId").asLong());
    assertTrue(first.get("hasUnreadMessages").asBoolean());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chat_read_positions", Integer.class));
    var opened = send("/market/listings/61/chat-rooms", 2, "POST", "", 200).get("data");
    assertEquals(10001, opened.get("roomId").asLong());
    assertEquals(
        10001,
        send("/market/listings/61/chat-rooms", 2, "POST", "", 200)
            .get("data")
            .get("roomId")
            .asLong());
    send("/market/listings/61/chat-rooms", 1, "POST", "", 403);
  }

  @Test
  void browserEntryPagingMessagesAndAccountIsolation() throws Exception {
    String script = System.getenv("CHAT_LIST_BROWSER_SCRIPT");
    assumeTrue(script != null, "Set CHAT_LIST_BROWSER_SCRIPT for actual Playwright validation");
    assertTrue(
        System.getenv("CHAT_LIST_TEST_DB_URL") != null,
        "Browser requires dedicated PostgreSQL and Redis");
    var process = new ProcessBuilder(System.getenv("CHAT_LIST_NODE"), script);
    Map<String, String> environment = process.environment();
    environment.clear();
    for (String name :
        List.of("SystemRoot", "WINDIR", "TEMP", "TMP", "USERPROFILE", "LOCALAPPDATA"))
      if (System.getenv(name) != null) environment.put(name, System.getenv(name));
    environment.put("CHAT_LIST_BASE_URL", baseUrl);
    environment.put("CHAT_LIST_PASSWORD", PASSWORD);
    environment.put("CHAT_LIST_PLAYWRIGHT", System.getenv("CHAT_LIST_PLAYWRIGHT"));
    environment.put("CHAT_LIST_EVIDENCE", System.getenv("CHAT_LIST_EVIDENCE"));
    Path log = Path.of(System.getenv("CHAT_LIST_EVIDENCE"), "browser.log");
    process.redirectErrorStream(true).redirectOutput(log.toFile());
    Process child = process.start();
    try {
      assertTrue(child.waitFor(180, TimeUnit.SECONDS), "Browser timeout; see browser.log");
      assertEquals(0, child.exitValue(), "Browser assertions failed; see browser.log");
    } finally {
      if (child.isAlive()) child.destroyForcibly();
    }
  }

  private JsonNode get(String path, long actor, int status) throws Exception {
    return send(path, actor, "GET", null, status);
  }

  private JsonNode send(String path, long actor, String method, String body, int status)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1" + path))
            .header("Authorization", "Bearer " + token(actor))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(status, response.statusCode(), method + " " + path);
    return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
  }

  private String token(long actor) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
    String claims =
        encoder.encodeToString(
            ("{\"sub\":\""
                    + actor
                    + "\",\"exp\":"
                    + Instant.now().plusSeconds(3600).getEpochSecond()
                    + "}")
                .getBytes(StandardCharsets.UTF_8));
    String unsigned = header + "." + claims;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return unsigned
        + "."
        + encoder.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
  }
}
