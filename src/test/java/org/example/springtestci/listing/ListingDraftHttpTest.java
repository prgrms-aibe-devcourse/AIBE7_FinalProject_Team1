package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.chat.service.ChatListingReader;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.service.ListingHistoryPublisher;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.example.springtestci.listing.service.ListingHistoryReader.Correction;
import org.example.springtestci.listing.service.ListingHistoryReader.Event;
import org.example.springtestci.listing.service.ListingHistoryReader.EventStatus;
import org.example.springtestci.listing.service.ListingHistoryReader.EventType;
import org.example.springtestci.listing.service.ListingHistoryReader.PublicText;
import org.example.springtestci.listing.service.ListingHistoryReader.Snapshot;
import org.example.springtestci.listing.service.ListingHistoryReader.State;
import org.example.springtestci.listing.service.ListingPublishConstraints;
import org.example.springtestci.product.service.SerialNumberProtector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTP/JWT, Flyway, JPA and JDBC tests. Set SALE_DRAFT_TEST_DB_* for PostgreSQL. */
class ListingDraftHttpTest {

  private static final String SECRET = "r01-test-only-signing-secret-at-least-32-bytes";
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static String baseUrl;
  private static String schema;
  private static Path storageRoot;
  private static final Map<Long, Long> SAVED_DRAFT_IDS = new ConcurrentHashMap<>();
  private static final AtomicReference<Snapshot> PUBLICATION_HISTORY =
      new AtomicReference<>(Snapshot.unavailable());
  private static final AtomicReference<ListingPublishConstraints.Snapshot> PUBLISH_CONSTRAINTS =
      new AtomicReference<>();
  private static final AtomicReference<Boolean> FAIL_HISTORY_PUBLICATION =
      new AtomicReference<>(false);

  @TestConfiguration(proxyBeanMethods = false)
  static class PublicationFixtureConfig {
    @Bean
    ListingHistoryReader listingHistoryTestReader() {
      return productId -> PUBLICATION_HISTORY.get();
    }

    @Bean
    ListingPublishConstraints listingPublishTestConstraints() {
      return productId -> PUBLISH_CONSTRAINTS.get();
    }

    @Bean
    ListingHistoryPublisher listingPublishTestHistory(JdbcTemplate jdbc) {
      return (productId, listingId, sellerId) -> {
        jdbc.update(
            "UPDATE listing_history_publication_probe SET visibility = 'PUBLIC', publication_count = publication_count + 1 WHERE product_id = ?",
            productId);
        if (FAIL_HISTORY_PUBLICATION.get())
          throw new CommonException(ErrorCode.LISTING_HISTORY_UNAVAILABLE);
      };
    }
  }

  @BeforeAll
  static void start() throws Exception {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    boolean postgres = url != null && !url.isBlank();
    if (postgres && !url.contains("sale_ai_test"))
      throw new IllegalStateException("Use a dedicated sale_ai_test database only");
    schema = postgres ? "sale_draft_r01_" + Long.toUnsignedString(System.nanoTime()) : "public";
    if (postgres) url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    else
      url =
          "jdbc:h2:mem:sale_draft_r01;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    storageRoot = Files.createTempDirectory("sale-draft-http-test-");
    try (var image =
        new ClassPathResource("static/images/hero-laptop-passport.png").getInputStream()) {
      Files.copy(image, storageRoot.resolve("draft-demo-front.png"));
    }
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.addPrimarySources(List.of(PublicationFixtureConfig.class));
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
    jdbc.execute(
        "CREATE TABLE listing_history_publication_probe(product_id BIGINT PRIMARY KEY, visibility VARCHAR(20) NOT NULL, publication_count INTEGER NOT NULL)");
    jdbc.update(
        "INSERT INTO users(id, email, password_hash, nickname, role, created_at, updated_at) VALUES (1, 'owner1@listing-fixture.invalid', 'not-a-login-hash', '판매테스트1', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), (2, 'owner2@listing-fixture.invalid', 'not-a-login-hash', '판매테스트2', 'USER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    var serials = context.getBean(SerialNumberProtector.class);
    for (long fixtureId : List.of(101L, 102L, 103L, 201L)) {
      jdbc.update(
          "UPDATE products SET serial_number_ciphertext = ? WHERE id = ?",
          serials.encrypt("R01-TEST-" + fixtureId),
          fixtureId);
    }
    baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) {
      if (!"public".equals(schema)) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      context.close();
    }
    if (storageRoot != null) {
      try (var paths = Files.walk(storageRoot)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  @BeforeEach
  void reset() {
    PUBLISH_CONSTRAINTS.set(new ListingPublishConstraints.Snapshot(true, false, false));
    FAIL_HISTORY_PUBLICATION.set(false);
    jdbc.update("DELETE FROM listing_images");
    jdbc.update("DELETE FROM listings");
    jdbc.update("DELETE FROM audit_logs WHERE action = 'LISTING_PUBLISHED'");
    jdbc.update("DELETE FROM listing_history_publication_probe");
    jdbc.update(
        "INSERT INTO listing_history_publication_probe VALUES (101, 'PRIVATE', 0), (102, 'PRIVATE', 0)");
    PUBLICATION_HISTORY.set(Snapshot.unavailable());
    SAVED_DRAFT_IDS.clear();
    jdbc.update("DELETE FROM draft_images");
    jdbc.update("DELETE FROM listing_drafts");
    jdbc.update("UPDATE products SET owner_id = 1 WHERE id = 101");
  }

  @Test
  void savesAnEmptyPrivateDraft() throws Exception {
    var response = save(101, "1", "{}");
    assertEquals(201, response.statusCode());
    var data = data(response);
    assertTrue(data.get("title").isNull());
    assertTrue(data.get("description").isNull());
    assertTrue(data.get("price").isNull());
    assertTrue(data.get("conditionGrade").isNull());
    assertEquals(0, data.get("selectedImageIds").size());
    assertEquals(0, data.get("version").asLong());
    assertNotNull(data.get("updatedAt").asText());
    assertEquals(
        "PRIVATE", jdbc.queryForObject("SELECT visibility FROM listing_drafts", String.class));
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
  }

  /** 쿠키 인증은 CSRF 토큰이 일치할 때만 쓰기 요청을 허용한다. */
  @Test
  void acceptsTheAccessTokenFromAnHttpOnlyCookieOnlyWithAMatchingCsrfToken() throws Exception {
    String path = "/api/v1/products/101/listing-draft";
    String cookie = "access_token=" + token("1") + "; XSRF-TOKEN=csrf-value";

    assertEquals(403, sendWithCookie(path, cookie, null).statusCode());
    assertEquals(403, sendWithCookie(path, cookie, "other-value").statusCode());
    assertEquals(201, sendWithCookie(path, cookie, "csrf-value").statusCode());
  }

  /** 인증 API의 쓰기 요청도 CSRF 토큰이 필요하다. */
  @Test
  void authEndpointsAlsoRequireACsrfTokenForStateChangingRequests() throws Exception {
    String cookie = "XSRF-TOKEN=csrf-value";

    assertEquals(403, sendWithCookie("/api/auth/login", cookie, null).statusCode());
    assertEquals(403, sendWithCookie("/api/auth/refresh", cookie, "other-value").statusCode());
    assertEquals(403, sendWithCookie("/api/auth/logout", cookie, null).statusCode());
    // CSRF를 통과한 요청만 컨트롤러에 도달한다(빈 본문이라 검증 오류 400).
    assertEquals(400, sendWithCookie("/api/auth/login", cookie, "csrf-value").statusCode());

    var logout = sendWithCookie("/api/auth/logout", cookie, "csrf-value");
    assertEquals(204, logout.statusCode());
    var cleared = logout.headers().allValues("Set-Cookie");
    assertTrue(cleared.stream().anyMatch(value -> value.startsWith("access_token=;")));
    assertTrue(cleared.stream().anyMatch(value -> value.startsWith("refresh_token=;")));
  }

  /** 잘못된 Access Token 쿠키는 화면 로딩을 막지 않고 API 요청에서만 거부한다. */
  @Test
  void anInvalidAccessCookieDoesNotBlockPagesButIsRejectedForApiRequests() throws Exception {
    String cookie = "access_token=not-a-valid-token";

    var page =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .header("Cookie", cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var api =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/me"))
                .header("Cookie", cookie)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(200, page.statusCode());
    assertEquals(401, api.statusCode());
  }

  /** 화면을 처음 받을 때 CSRF 쿠키를 내려준다. */
  @Test
  void issuesTheCsrfCookieWhenAPageIsFirstLoaded() throws Exception {
    var response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(baseUrl + "/")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

    assertTrue(
        response.headers().allValues("Set-Cookie").stream()
            .anyMatch(value -> value.startsWith("XSRF-TOKEN=")));
  }

  @Test
  void savesContentAndSelectedPhotoOrderWithTheFirstAsRepresentative() throws Exception {
    var response =
        save(
            101,
            "1",
            """
        {"title":"내 노트북", "description":"배터리 교체 기록을 확인해 주세요.",
         "price":2150000,"conditionGrade":4,"selectedImageIds":[303,301,302]}
        """);
    assertEquals(201, response.statusCode());
    assertEquals("내 노트북", data(response).get("title").asText());
    assertEquals(2150000, data(response).get("price").asLong());
    assertEquals(
        List.of(303L, 301L, 302L),
        jdbc.queryForList(
            "SELECT product_image_id FROM draft_images ORDER BY sort_order", Long.class));
    assertEquals(303, data(response).get("selectedImageIds").get(0).asLong());
    assertEquals(
        List.of(303L),
        jdbc.queryForList(
            "SELECT product_image_id FROM draft_images WHERE is_primary = TRUE", Long.class));
  }

  @Test
  void normalizesBlankOptionalTextToNull() throws Exception {
    var response =
        save(101, "1", "{\"title\":\"  \",\"description\":\" \",\"selectedImageIds\":null}");
    assertEquals(201, response.statusCode());
    assertTrue(data(response).get("title").isNull());
    assertTrue(data(response).get("description").isNull());
    assertTrue(data(response).get("selectedImageIds").isArray());
  }

  @Test
  void rejectsNonPositiveFractionalAndOversizedPrices() throws Exception {
    for (String price : List.of("0", "-1", "1.5", "1000000000000")) {
      assertEquals(400, save(101, "1", "{\"price\":" + price + "}").statusCode(), price);
    }
    assertEquals(0, countDrafts());
  }

  @Test
  void rejectsGradesOutsideOneThroughFive() throws Exception {
    assertEquals(400, save(101, "1", "{\"conditionGrade\":0}").statusCode());
    assertEquals(400, save(101, "1", "{\"conditionGrade\":6}").statusCode());
    assertEquals(201, save(101, "1", "{\"conditionGrade\":1}").statusCode());
  }

  @Test
  void rejectsTextBeyondTheScreenLimits() throws Exception {
    assertEquals(
        400, save(101, "1", JSON.writeValueAsString(Map.of("title", "a".repeat(61)))).statusCode());
    assertEquals(
        400,
        save(101, "1", JSON.writeValueAsString(Map.of("description", "a".repeat(2001))))
            .statusCode());
  }

  @Test
  void rejectsForeignDeletedAndNonProductPhotos() throws Exception {
    for (long image : List.of(304L, 401L, 399L, 999L)) {
      assertEquals(400, save(101, "1", "{\"selectedImageIds\":[" + image + "]}").statusCode());
    }
    assertEquals(0, countDrafts());
  }

  @Test
  void rejectsDuplicateNullAndOversizedPhotoSelections() throws Exception {
    assertEquals(400, save(101, "1", "{\"selectedImageIds\":[301,301]}").statusCode());
    assertEquals(400, save(101, "1", "{\"selectedImageIds\":[null]}").statusCode());
    assertEquals(400, save(101, "1", "{\"selectedImageIds\":[0]}").statusCode());
    assertEquals(
        400,
        save(
                101,
                "1",
                JSON.writeValueAsString(
                    Map.of("selectedImageIds", java.util.Collections.nCopies(21, 301))))
            .statusCode());
  }

  /** 로그인과 현재 소유자 여부를 확인한다. */
  @Test
  void requiresAuthenticationAndTheCurrentOwner() throws Exception {
    // CSRF 토큰은 맞지만 로그인하지 않은 요청
    assertEquals(
        401,
        sendWithCookie("/api/v1/products/101/listing-draft", "XSRF-TOKEN=csrf-value", "csrf-value")
            .statusCode());
    // CSRF 토큰도 없는 비로그인 쓰기 요청은 CSRF 검사에서 먼저 거부된다.
    assertEquals(403, save(101, null, "{}").statusCode());
    assertEquals(404, save(101, "2", "{}").statusCode());
    assertEquals(404, save(103, "1", "{}").statusCode());
    assertEquals(404, save(999, "1", "{}").statusCode());
    assertEquals(0, countDrafts());
  }

  @Test
  void derivesAuthorAndPrivacyFromTheServerEvenIfTheClientSuppliesThem() throws Exception {
    var response =
        save(
            101,
            "1",
            "{\"authorId\":2,\"productId\":201,\"visibility\":\"PUBLIC\",\"status\":\"ACTIVE\"}");
    assertEquals(201, response.statusCode());
    assertEquals(1, jdbc.queryForObject("SELECT author_id FROM listing_drafts", Long.class));
    assertEquals(101, jdbc.queryForObject("SELECT product_id FROM listing_drafts", Long.class));
    assertEquals(
        "PRIVATE", jdbc.queryForObject("SELECT visibility FROM listing_drafts", String.class));
  }

  @Test
  void returnsOnlyOwnedUnarchivedProductsAndSafeComposition() throws Exception {
    var list = send("GET", "/api/v1/listing-draft-products", "1", null);
    assertEquals(200, list.statusCode());
    assertEquals(2, data(list).size());
    var composition = send("GET", "/api/v1/products/101/listing-draft/composition", "1", null);
    assertEquals(3, data(composition).get("images").size());
    assertEquals(18, data(composition).get("specifications").get("ramGb").asInt());
    for (String field : List.of("ownerId", "serialNumber", "storageKey", "documents"))
      assertFalse(composition.body().contains(field));
    assertEquals(
        404, send("GET", "/api/v1/products/101/listing-draft/composition", "2", null).statusCode());
  }

  @Test
  void updatesTheSameDraftUsingItsReturnedVersion() throws Exception {
    var first = save(101, "1", "{\"selectedImageIds\":[301,302]}");
    var update =
        save(101, "1", "{\"version\":0,\"title\":\"수정한 제목\",\"selectedImageIds\":[303,301]}");
    assertEquals(200, update.statusCode());
    assertEquals(data(first).get("draftId").asLong(), data(update).get("draftId").asLong());
    assertEquals(1, data(update).get("version").asLong());
    assertEquals(1, countDrafts());
    assertEquals(
        List.of(303L, 301L),
        jdbc.queryForList(
            "SELECT product_image_id FROM draft_images ORDER BY sort_order", Long.class));
  }

  @Test
  void productOptionsShowOpenListingsBeforeCompositionAndOnlyForTheCurrentOwner() throws Exception {
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES(8001,101,1,'mine',100000,4,'test','ACTIVE',CURRENT_TIMESTAMP,9001,0,1),(8002,201,2,'other',100000,4,'test','RESERVED',CURRENT_TIMESTAMP,9002,0,1)");
    for (String status : List.of("ACTIVE", "RESERVED")) {
      jdbc.update("UPDATE listings SET status=? WHERE id=8001", status);
      var response = send("GET", "/api/v1/listing-draft-products", "1", null);
      assertEquals(200, response.statusCode());
      assertEquals(2, data(response).size());
      for (var product : data(response)) {
        long id = product.get("productId").asLong();
        assertTrue(id == 101 || id == 102);
        assertEquals(id == 102, product.get("canCompose").asBoolean());
        if (id == 101) {
          assertEquals(8001, product.get("listingId").asLong());
          assertEquals(status, product.get("listingStatus").asString());
        }
      }
      assertFalse(response.body().contains("serialNumber"));
      assertEquals(
          409,
          send("GET", "/api/v1/products/101/listing-draft/composition", "1", null).statusCode());
    }
    var other = data(send("GET", "/api/v1/listing-draft-products", "2", null));
    assertEquals(1, other.size());
    assertEquals(201, other.get(0).get("productId").asLong());
    assertEquals(8002, other.get(0).get("listingId").asLong());
    jdbc.update("UPDATE listings SET status='CANCELLED', active_slot=NULL WHERE id=8001");
    for (var product : data(send("GET", "/api/v1/listing-draft-products", "1", null))) {
      assertTrue(product.get("canCompose").asBoolean());
      assertTrue(product.get("listingId").isNull());
      assertTrue(product.get("listingStatus").isNull());
    }
  }

  @Test
  void photoOnlyChangesAdvanceTheDraftVersionAndPreserveTheProductPhotoOrder() throws Exception {
    save(101, "1", "{\"selectedImageIds\":[301,302]}");
    var update = save(101, "1", "{\"version\":0,\"selectedImageIds\":[302,301]}");
    assertEquals(200, update.statusCode());
    assertEquals(1, data(update).get("version").asLong());
    assertEquals(
        List.of(302L, 301L),
        jdbc.queryForList(
            "SELECT product_image_id FROM draft_images ORDER BY sort_order", Long.class));
    assertEquals(
        List.of(302L),
        jdbc.queryForList(
            "SELECT product_image_id FROM draft_images WHERE is_primary = TRUE", Long.class));
    assertEquals(
        List.of(301L, 302L, 303L),
        jdbc.queryForList(
            "SELECT id FROM product_images WHERE product_id = 101 AND deleted_at IS NULL ORDER BY sort_order",
            Long.class));
    assertEquals(409, save(101, "1", "{\"version\":0,\"selectedImageIds\":[303]}").statusCode());
  }

  @Test
  void clearingSelectedPhotosRemovesTheirRowsAndRepresentative() throws Exception {
    save(101, "1", "{\"selectedImageIds\":[301,302]}");
    var update = save(101, "1", "{\"version\":0,\"selectedImageIds\":[]}");
    assertEquals(200, update.statusCode());
    assertEquals(1, data(update).get("version").asLong());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM draft_images", Integer.class));
  }

  @Test
  void deletingASelectedProductPhotoRepairsTheDraftAndRejectsItsOldVersion() throws Exception {
    save(101, "1", "{\"selectedImageIds\":[301,303,302]}");
    try {
      var deleted = send("DELETE", "/api/v1/products/101/images/301", "1", null);
      assertEquals(200, deleted.statusCode());
      assertEquals(
          List.of(303L, 302L),
          jdbc.queryForList(
              "SELECT product_image_id FROM draft_images ORDER BY sort_order", Long.class));
      assertEquals(
          List.of(0, 1),
          jdbc.queryForList(
              "SELECT sort_order FROM draft_images ORDER BY sort_order", Integer.class));
      assertEquals(
          List.of(303L),
          jdbc.queryForList(
              "SELECT product_image_id FROM draft_images WHERE is_primary = TRUE", Long.class));
      assertEquals(1L, jdbc.queryForObject("SELECT version FROM listing_drafts", Long.class));
      assertEquals(
          409, save(101, "1", "{\"version\":0,\"selectedImageIds\":[303,302]}").statusCode());
      assertEquals(
          404,
          send("GET", "/api/v1/products/101/listing-draft/images/301", "1", null).statusCode());
    } finally {
      jdbc.update(
          "UPDATE product_images SET sort_order = sort_order + 2000000 WHERE id IN (301,302,303)");
      jdbc.update(
          "UPDATE product_images SET deleted_at = NULL, sort_order = id - 301, is_primary = FALSE WHERE id IN (301,302,303)");
      try (var image =
          new ClassPathResource("static/images/hero-laptop-passport.png").getInputStream()) {
        Files.copy(
            image,
            storageRoot.resolve("draft-demo-front.png"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  @Test
  void productRegisteredThroughTheProductApiCanBeUsedForADraft() throws Exception {
    var registered =
        send(
            "POST",
            "/api/v1/products",
            "1",
            """
        {"title":"새 제품", "manufacturer":"Lenovo", "modelName":"ThinkPad",
         "serialNumber":"R01-REAL-PRODUCT", "specifications":{
           "cpu":"Intel Core Ultra 7", "ramGb":32, "storageGb":1024, "storageType":"SSD"}}
        """);
    assertEquals(201, registered.statusCode());
    assertFalse(data(registered).get("serialNumber").asText().isBlank());
    long createdProductId = data(registered).get("productId").asLong();
    try {
      var composition =
          send(
              "GET",
              "/api/v1/products/" + createdProductId + "/listing-draft/composition",
              "1",
              null);
      assertEquals(200, composition.statusCode());
      assertEquals(data(registered).get("specifications"), data(composition).get("specifications"));
      assertEquals(0, data(composition).get("images").size());
      assertFalse(composition.body().contains("serialNumber"));
      var uploaded = uploadPhoto(createdProductId);
      assertEquals(201, uploaded.statusCode());
      long uploadedImageId = data(uploaded).get("images").get(0).get("imageId").asLong();
      var withPhoto =
          send(
              "GET",
              "/api/v1/products/" + createdProductId + "/listing-draft/composition",
              "1",
              null);
      assertEquals(uploadedImageId, data(withPhoto).get("images").get(0).get("imageId").asLong());
      assertEquals(
          200,
          send("GET", data(withPhoto).get("images").get(0).get("url").asText(), "1", null)
              .statusCode());
      var saved =
          save(
              createdProductId,
              "1",
              "{\"title\":\"판매 초안\",\"selectedImageIds\":[" + uploadedImageId + "]}");
      assertEquals(201, saved.statusCode());
      assertEquals(createdProductId, data(saved).get("productId").asLong());
      assertEquals(uploadedImageId, data(saved).get("selectedImageIds").get(0).asLong());
    } finally {
      jdbc.update("DELETE FROM listing_drafts WHERE product_id = ?", createdProductId);
      jdbc.update("DELETE FROM product_images WHERE product_id = ?", createdProductId);
      jdbc.update("DELETE FROM product_field_authors WHERE product_id = ?", createdProductId);
      jdbc.update("DELETE FROM audit_logs WHERE product_id = ?", createdProductId);
      jdbc.update("DELETE FROM laptop_details WHERE product_id = ?", createdProductId);
      jdbc.update("DELETE FROM products WHERE id = ?", createdProductId);
    }
  }

  @Test
  void databaseEnforcesPhotoReferencesUniquenessAndCascadeDeletion() throws Exception {
    var saved = save(101, "1", "{\"selectedImageIds\":[301,302]}");
    long draftId = data(saved).get("draftId").asLong();
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO draft_images (draft_id, product_image_id, sort_order, is_primary) VALUES (?, 301, 2, FALSE)",
                draftId));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO draft_images (draft_id, product_image_id, sort_order, is_primary) VALUES (?, 303, 1, FALSE)",
                draftId));
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO draft_images (draft_id, product_image_id, sort_order, is_primary) VALUES (?, 999, 2, FALSE)",
                draftId));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE draft_images SET is_primary = TRUE WHERE sort_order = 1"));
    jdbc.update("DELETE FROM listing_drafts WHERE id = ?", draftId);
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM draft_images", Integer.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM product_images WHERE product_id = 101 AND deleted_at IS NULL",
            Integer.class));
  }

  @Test
  void refusesMissingAndStaleVersionsWithoutLosingSavedContent() throws Exception {
    save(101, "1", "{\"title\":\"처음 제목\"}");
    assertEquals(409, save(101, "1", "{\"title\":\"버전 없음\"}").statusCode());
    save(101, "1", "{\"version\":0,\"title\":\"최신 제목\"}");
    assertEquals(409, save(101, "1", "{\"version\":0,\"title\":\"오래된 제목\"}").statusCode());
    assertEquals("최신 제목", jdbc.queryForObject("SELECT title FROM listing_drafts", String.class));
  }

  @Test
  void serializesConcurrentCreatesWithOneDraftAndOneConflict() throws Exception {
    var first = CompletableFuture.supplyAsync(() -> uncheckedSave("{}"));
    var second = CompletableFuture.supplyAsync(() -> uncheckedSave("{}"));
    var statuses = List.of(first.join(), second.join()).stream().sorted().toList();
    assertEquals(List.of(201, 409), statuses);
    assertEquals(1, countDrafts());
  }

  @Test
  void serializesConcurrentEditsUsingTheSameVersion() throws Exception {
    save(101, "1", "{}");
    var first =
        CompletableFuture.supplyAsync(() -> uncheckedSave("{\"version\":0,\"title\":\"첫번째\"}"));
    var second =
        CompletableFuture.supplyAsync(() -> uncheckedSave("{\"version\":0,\"title\":\"두번째\"}"));
    assertEquals(
        List.of(200, 409), List.of(first.join(), second.join()).stream().sorted().toList());
    assertEquals(1, jdbc.queryForObject("SELECT version FROM listing_drafts", Long.class));
  }

  @Test
  void rechecksOwnershipAfterTransferAndDoesNotExposeTheOldOwnersDraft() throws Exception {
    save(101, "1", "{\"title\":\"비공개 작성 내용\"}");
    jdbc.update("UPDATE products SET owner_id = 2 WHERE id = 101");
    assertEquals(404, save(101, "1", "{\"version\":0}").statusCode());
    assertEquals(
        404, send("GET", "/api/v1/products/101/listing-draft/images/301", "1", null).statusCode());
    assertEquals(409, save(101, "2", "{}").statusCode());
    assertEquals(404, send("GET", "/api/v1/products/101/listing-draft", "2", null).statusCode());
  }

  @Test
  void readsSavedContentAndPhotoOrderAfterReopeningTheProduct() throws Exception {
    var created =
        save(
            101,
            "1",
            """
        {"title":"이어서 쓸 제목", "description":"저장한 설명", "price":123456,
         "conditionGrade":4, "selectedImageIds":[303,301]}
        """);
    var reopened = readDraft(101, "1");
    assertEquals(200, reopened.statusCode());
    for (String field :
        List.of(
            "draftId",
            "productId",
            "title",
            "description",
            "price",
            "conditionGrade",
            "selectedImageIds",
            "version")) {
      assertEquals(data(created).get(field), data(reopened).get(field), field);
    }
    // DB의 timestamp(6) 저장 정밀도에 따른 나노초 반올림만 허용한다.
    assertTrue(
        Duration.between(
                    Instant.parse(data(created).get("updatedAt").asText()),
                    Instant.parse(data(reopened).get("updatedAt").asText()))
                .abs()
                .compareTo(Duration.ofNanos(1000))
            <= 0);
    assertEquals("no-store", reopened.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals(404, readDraft(102, "1").statusCode());
    assertEquals(404, readDraft(101, "2").statusCode());
    assertEquals(401, readDraft(101, null).statusCode());
    var resumedSave =
        updateDraft(
            101,
            data(reopened).get("draftId").asLong(),
            "1",
            "{\"version\":0,\"title\":\"이어서 수정한 제목\",\"selectedImageIds\":[301,303]}");
    assertEquals(200, resumedSave.statusCode());
    assertEquals(1, data(readDraft(101, "1")).get("version").asLong());
    assertEquals("이어서 수정한 제목", data(readDraft(101, "1")).get("title").asText());
  }

  @Test
  void listsDraftsWithStablePaginationAndNoPrivateProductFields() throws Exception {
    var first = save(101, "1", "{\"title\":\"첫 초안\",\"description\":\"목록에 노출하지 않을 본문\"}");
    var second = save(102, "1", "{}");
    save(201, "2", "{\"title\":\"다른 사람 초안\"}");
    jdbc.update("UPDATE listing_drafts SET updated_at = created_at WHERE product_id = 101");
    jdbc.update(
        "UPDATE listing_drafts SET updated_at = (SELECT updated_at FROM listing_drafts WHERE product_id = 101) WHERE product_id = 102");
    var page0 = send("GET", "/api/v1/listing-drafts?page=0&size=1", "1", null);
    var page1 = send("GET", "/api/v1/listing-drafts?page=1&size=1", "1", null);
    assertEquals(200, page0.statusCode());
    assertEquals(2, data(page0).get("page").get("totalElements").asLong());
    assertEquals(2, data(page0).get("page").get("totalPages").asInt());
    assertTrue(data(page0).get("page").get("hasNext").asBoolean());
    assertFalse(data(page1).get("page").get("hasNext").asBoolean());
    assertEquals(
        data(second).get("draftId").asLong(),
        data(page0).get("items").get(0).get("draftId").asLong());
    assertEquals(
        data(first).get("draftId").asLong(),
        data(page1).get("items").get(0).get("draftId").asLong());
    assertTrue(data(page0).get("items").get(0).get("title").isNull());
    for (String field :
        List.of("description", "serialNumber", "ownerId", "authorId", "documents", "storageKey")) {
      assertFalse(page0.body().contains(field));
    }
    assertEquals("no-store", page0.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals(
        0,
        data(send("GET", "/api/v1/listing-drafts?page=50&size=1", "1", null)).get("items").size());
  }

  @Test
  void filtersTransferredAndArchivedDraftsBeforePagination() throws Exception {
    save(101, "1", "{\"title\":\"보이지 않아야 할 초안\"}");
    save(102, "1", "{\"title\":\"내 초안\"}");
    try {
      jdbc.update("UPDATE products SET owner_id = 2 WHERE id = 101");
      assertEquals(
          1,
          data(send("GET", "/api/v1/listing-drafts?size=1", "1", null))
              .get("page")
              .get("totalElements")
              .asLong());
      assertEquals(
          102,
          data(send("GET", "/api/v1/listing-drafts?size=1", "1", null))
              .get("items")
              .get(0)
              .get("productId")
              .asLong());
      assertEquals(0, data(send("GET", "/api/v1/listing-drafts", "2", null)).get("items").size());
      jdbc.update("UPDATE products SET archived_at = CURRENT_TIMESTAMP WHERE id = 102");
      assertEquals(
          0,
          data(send("GET", "/api/v1/listing-drafts", "1", null))
              .get("page")
              .get("totalElements")
              .asLong());
      assertEquals(404, readDraft(102, "1").statusCode());
    } finally {
      jdbc.update("UPDATE products SET owner_id = 1 WHERE id = 101");
      jdbc.update("UPDATE products SET archived_at = NULL WHERE id = 102");
    }
  }

  @Test
  void validatesListPaginationAndRequiresAuthentication() throws Exception {
    assertEquals(401, send("GET", "/api/v1/listing-drafts", null, null).statusCode());
    for (String query : List.of("page=-1", "size=0", "size=101", "page=abc")) {
      assertEquals(400, send("GET", "/api/v1/listing-drafts?" + query, "1", null).statusCode());
    }
    assertEquals(0, data(send("GET", "/api/v1/listing-drafts", "1", null)).get("items").size());
  }

  @Test
  void updateRequiresAnExistingDraftItsIdentityAndVersion() throws Exception {
    assertEquals(404, updateDraft(101, 999, "1", "{\"version\":0}").statusCode());
    assertEquals(0, countDrafts());
    long id = data(save(101, "1", "{\"title\":\"보존할 제목\"}")).get("draftId").asLong();
    assertEquals(
        400,
        send("PUT", "/api/v1/products/101/listing-draft", "1", "{\"version\":0}").statusCode());
    assertEquals(409, updateDraft(101, id, "1", "{}").statusCode());
    assertEquals(409, updateDraft(101, id + 999, "1", "{\"version\":0}").statusCode());
    assertEquals(404, updateDraft(101, id, "2", "{\"version\":0}").statusCode());
    assertEquals(400, updateDraft(101, id, "1", "{\"version\":0,\"price\":-1}").statusCode());
    assertEquals("보존할 제목", data(readDraft(101, "1")).get("title").asText());
    assertEquals(
        409,
        send(
                "POST",
                "/api/v1/products/101/listing-draft",
                "1",
                "{\"version\":0,\"title\":\"POST 수정 금지\"}")
            .statusCode());
  }

  @Test
  void deletesOnlyTheDraftAndItsPhotoSelections() throws Exception {
    long id = data(save(101, "1", "{\"selectedImageIds\":[303,301]}")).get("draftId").asLong();
    assertEquals(403, deleteDraft(101, id, null, 0).statusCode());
    assertEquals(404, deleteDraft(101, id, "2", 0).statusCode());
    assertEquals(
        400,
        send("DELETE", "/api/v1/products/101/listing-draft?draftId=" + id, "1", null).statusCode());
    assertEquals(409, deleteDraft(101, id + 99, "1", 0).statusCode());
    var deleted = deleteDraft(101, id, "1", 0);
    assertEquals(204, deleted.statusCode());
    assertEquals("", deleted.body());
    assertEquals("no-store", deleted.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals(0, countDrafts());
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM draft_images", Integer.class));
    assertEquals(
        1, jdbc.queryForObject("SELECT count(*) FROM products WHERE id = 101", Integer.class));
    assertEquals(
        3,
        jdbc.queryForObject(
            "SELECT count(*) FROM product_images WHERE product_id = 101 AND deleted_at IS NULL",
            Integer.class));
    assertEquals(
        200, send("GET", "/api/v1/products/101/listing-draft/images/301", "1", null).statusCode());
    assertEquals(404, readDraft(101, "1").statusCode());
    assertEquals(404, deleteDraft(101, id, "1", 0).statusCode());
    assertEquals(201, save(101, "1", "{}").statusCode());
  }

  @Test
  void updatesAndDeletesCookieAuthenticatedDraftsOnlyWithMatchingCsrf() throws Exception {
    long id = data(save(101, "1", "{}")).get("draftId").asLong();
    String cookie = "access_token=" + token("1") + "; XSRF-TOKEN=csrf-value";
    String path = "/api/v1/products/101/listing-draft?draftId=" + id;
    String body = "{\"version\":0,\"title\":\"쿠키 인증으로 수정\"}";
    assertEquals(403, sendWithCookie("PUT", path, cookie, null, body).statusCode());
    assertEquals(403, sendWithCookie("PUT", path, cookie, "wrong-value", body).statusCode());
    assertEquals(
        401, sendWithCookie("PUT", path, "XSRF-TOKEN=csrf-value", "csrf-value", body).statusCode());
    var updated = sendWithCookie("PUT", path, cookie, "csrf-value", body);
    assertEquals(200, updated.statusCode());
    assertEquals("쿠키 인증으로 수정", data(updated).get("title").asText());
    path += "&version=" + data(updated).get("version").asLong();
    assertEquals(403, sendWithCookie("DELETE", path, cookie, null, null).statusCode());
    assertEquals(403, sendWithCookie("DELETE", path, cookie, "wrong-value", null).statusCode());
    assertEquals(
        401,
        sendWithCookie("DELETE", path, "XSRF-TOKEN=csrf-value", "csrf-value", null).statusCode());
    assertEquals(1, countDrafts());
    assertEquals(204, sendWithCookie("DELETE", path, cookie, "csrf-value", null).statusCode());
    assertEquals(0, countDrafts());
  }

  @Test
  void staleDeletePreservesTheUpdatedDraft() throws Exception {
    long id = data(save(101, "1", "{\"title\":\"처음 제목\"}")).get("draftId").asLong();
    updateDraft(101, id, "1", "{\"version\":0,\"title\":\"최신 제목\"}");
    assertEquals(409, deleteDraft(101, id, "1", 0).statusCode());
    assertEquals("최신 제목", data(readDraft(101, "1")).get("title").asText());
    assertEquals(204, deleteDraft(101, id, "1", 1).statusCode());
  }

  @Test
  void anOldTabCannotUpdateOrDeleteADraftRecreatedAtVersionZero() throws Exception {
    long oldId = data(save(101, "1", "{\"title\":\"옛 초안\"}")).get("draftId").asLong();
    assertEquals(204, deleteDraft(101, oldId, "1", 0).statusCode());
    long newId = data(save(101, "1", "{\"title\":\"새 초안\"}")).get("draftId").asLong();
    assertNotEquals(oldId, newId);
    assertEquals(
        409, updateDraft(101, oldId, "1", "{\"version\":0,\"title\":\"옛 창에서 덮어쓰기\"}").statusCode());
    assertEquals(409, deleteDraft(101, oldId, "1", 0).statusCode());
    assertEquals("새 초안", data(readDraft(101, "1")).get("title").asText());
  }

  @Test
  void serializesConcurrentUpdateAndDeleteWithoutLosingANewerDraft() throws Exception {
    long id = data(save(101, "1", "{}")).get("draftId").asLong();
    var update =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return updateDraft(101, id, "1", "{\"version\":0,\"title\":\"동시 수정\"}")
                    .statusCode();
              } catch (Exception exception) {
                throw new RuntimeException(exception);
              }
            });
    var deletion =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return deleteDraft(101, id, "1", 0).statusCode();
              } catch (Exception exception) {
                throw new RuntimeException(exception);
              }
            });
    var codes = List.of(update.join(), deletion.join());
    assertTrue(
        codes.equals(List.of(200, 409)) || codes.equals(List.of(404, 204)), codes.toString());
  }

  @Test
  void transferBlocksBothPreviousAndNewOwnerFromThePreviousOwnersDraft() throws Exception {
    long id = data(save(101, "1", "{\"title\":\"이전 소유자 비공개\"}")).get("draftId").asLong();
    jdbc.update("UPDATE products SET owner_id = 2 WHERE id = 101");
    for (String user : List.of("1", "2")) {
      assertEquals(404, readDraft(101, user).statusCode());
      assertEquals(404, updateDraft(101, id, user, "{\"version\":0}").statusCode());
      assertEquals(404, deleteDraft(101, id, user, 0).statusCode());
      assertEquals(0, data(send("GET", "/api/v1/listing-drafts", user, null)).get("items").size());
    }
  }

  @Test
  void servesProductImagesWithOwnershipChecksAndWithoutCaching() throws Exception {
    var response = send("GET", "/api/v1/products/101/listing-draft/images/301", "1", null);
    assertEquals(200, response.statusCode());
    assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals(
        404, send("GET", "/api/v1/products/101/listing-draft/images/301", "2", null).statusCode());
    assertEquals(
        404, send("GET", "/api/v1/products/101/listing-draft/images/401", "1", null).statusCode());
    assertEquals(
        401, send("GET", "/api/v1/products/101/listing-draft/images/301", null, null).statusCode());
  }

  @Test
  void databaseAlsoPreventsAPublicDraft() throws Exception {
    save(101, "1", "{}");
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE listing_drafts SET visibility = 'PUBLIC'"));
    assertEquals(
        "PRIVATE", jdbc.queryForObject("SELECT visibility FROM listing_drafts", String.class));
  }

  @Test
  void publicationReadsSafeProductFieldsWithoutSavingOrDecryptingSerial() throws Exception {
    jdbc.update(
        "UPDATE products SET title = '개인 별칭 비공개', serial_number_ciphertext = 'not-a-cipher' WHERE id = 101");
    try {
      var response = publication(101, "1", "");
      assertEquals(200, response.statusCode());
      assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
      var product = data(response).get("product");
      assertEquals("Apple", product.get("manufacturer").asText());
      assertEquals(18, product.get("specifications").get("ramGb").asInt());
      assertEquals("UNAVAILABLE", data(response).get("history").get("status").asText());
      for (String excluded :
          List.of(
              "개인 별칭 비공개",
              "not-a-cipher",
              "serialNumber",
              "ownerId",
              "storageKey",
              "objectKey",
              "documents",
              "cost",
              "warrantyTerms")) assertFalse(response.body().contains(excluded), excluded);
      assertEquals(0, countDrafts());
    } finally {
      jdbc.update(
          "UPDATE products SET title = 'MacBook Pro 14 M3 Pro', serial_number_ciphertext = ? WHERE id = 101",
          context.getBean(SerialNumberProtector.class).encrypt("R01-TEST-101"));
    }
  }

  @Test
  void publicationDistinguishesUnavailableHistoryFromAnEmptyReadySource() throws Exception {
    assertEquals(
        "UNAVAILABLE", data(publication(101, "1", "")).get("history").get("status").asText());
    PUBLICATION_HISTORY.set(new Snapshot(State.READY, false, List.of()));
    var history = data(publication(101, "1", "")).get("history");
    assertEquals("READY", history.get("status").asText());
    assertEquals(0, history.get("items").size());
    assertEquals(0, history.get("page").get("totalElements").asInt());
    assertFalse(history.get("sample").asBoolean());
  }

  @Test
  void publicationRequiresTheCurrentOwnerAndValidPagination() throws Exception {
    assertEquals(401, publication(101, null, "").statusCode());
    for (long id : List.of(201L, 103L, 999L))
      assertEquals(404, publication(id, "1", "").statusCode());
    assertEquals(404, publication(101, "2", "").statusCode());
    for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=wrong"))
      assertEquals(400, publication(101, "1", query).statusCode());
    jdbc.update("UPDATE products SET owner_id = 2 WHERE id = 101");
    assertEquals(404, publication(101, "1", "").statusCode());
    assertEquals(200, publication(101, "2", "").statusCode());
  }

  @Test
  void publicationKeepsPreviousOwnerAndVoidEventsInStablePaginatedOrder() throws Exception {
    var earlier =
        historyEvent(701, 2L, EventType.PURCHASE, EventStatus.VALID, "2024-01-01", null, List.of());
    var first =
        historyEvent(702, 1L, EventType.REPAIR, EventStatus.VALID, "2025-01-01", null, List.of());
    var second =
        historyEvent(
            703, 2L, EventType.INSPECTION, EventStatus.VALID, "2025-01-01", null, List.of());
    var invalid =
        historyEvent(704, 2L, EventType.REPAIR, EventStatus.VOID, "2025-02-01", null, List.of());
    PUBLICATION_HISTORY.set(
        new Snapshot(State.READY, false, List.of(first, earlier, invalid, second)));
    var page = data(publication(101, "1", "?size=2")).get("history");
    assertEquals(704, page.get("items").get(0).get("eventId").asInt());
    assertEquals("VOID", page.get("items").get(0).get("status").asText());
    assertTrue(page.get("items").get(0).get("previousOwner").asBoolean());
    assertEquals(703, page.get("items").get(1).get("eventId").asInt());
    assertEquals(3, page.get("validCount").asInt());
    assertEquals(1, page.get("voidCount").asInt());
    assertEquals(4, page.get("page").get("totalElements").asInt());
    assertTrue(page.get("page").get("hasNext").asBoolean());
    var next = data(publication(101, "1", "?page=1&size=2")).get("history");
    assertEquals(702, next.get("items").get(0).get("eventId").asInt());
    assertFalse(next.get("items").get(0).get("previousOwner").asBoolean());
    assertEquals(701, next.get("items").get(1).get("eventId").asInt());
    assertFalse(next.get("page").get("hasNext").asBoolean());
    assertEquals(
        0, data(publication(101, "1", "?page=999&size=2")).get("history").get("items").size());
  }

  @Test
  void publicationShowsReviewedCorrectionsButHidesUnreviewedAndSensitiveText() throws Exception {
    var corrected =
        historyEvent(
            705,
            2L,
            EventType.INSPECTION,
            EventStatus.VALID,
            "2025-03-01",
            new PublicText("정기 점검", "점검 완료", "공식 서비스센터", null, true),
            List.of(
                new Correction(901, Instant.parse("2025-03-02T08:00:00Z"), "발생일 정정", true),
                new Correction(902, Instant.parse("2025-03-02T08:00:00Z"), "개인 이름 미검토", false)));
    var pending =
        historyEvent(
            706,
            2L,
            EventType.REPAIR,
            EventStatus.VOID,
            "2025-02-01",
            new PublicText(
                "개인 이름 미검토",
                "a@private.example 010-1234-5678 500000원 SERIAL-SECRET",
                "서울시 비공개 주소",
                "비공개 값",
                false),
            List.of());
    var unsafeApproved =
        historyEvent(
            707,
            2L,
            EventType.REPAIR,
            EventStatus.VALID,
            "2025-01-01",
            new PublicText("점검", "a@private.example", null, null, true),
            List.of());
    var transfer =
        historyEvent(
            708,
            null,
            EventType.TRANSFER,
            EventStatus.VALID,
            "2024-12-01",
            new PublicText("보내는 사람 이름", "받는 사람 이름", null, null, true),
            List.of());
    PUBLICATION_HISTORY.set(
        new Snapshot(State.READY, false, List.of(pending, corrected, transfer, unsafeApproved)));
    var response = publication(101, "1", "");
    var items = data(response).get("history").get("items");
    assertEquals("정기 점검", items.get(0).get("title").asText());
    assertEquals(902, items.get(0).get("corrections").get(0).get("correctionId").asInt());
    assertTrue(items.get(0).get("corrections").get(0).get("reason").isNull());
    assertEquals("발생일 정정", items.get(0).get("corrections").get(1).get("reason").asText());
    assertEquals("REVIEW_PENDING", items.get(1).get("detailsStatus").asText());
    assertEquals("제품 수리", items.get(1).get("title").asText());
    assertTrue(items.get(1).get("content").isNull());
    assertEquals("REVIEWED", items.get(2).get("detailsStatus").asText());
    assertEquals("점검", items.get(2).get("title").asText());
    assertTrue(items.get(2).get("content").isNull());
    assertEquals("소유권 이전 완료", items.get(3).get("title").asText());
    assertTrue(items.get(3).get("systemGenerated").asBoolean());
    for (String excluded :
        List.of(
            "개인 이름 미검토",
            "private.example",
            "010-1234",
            "500000",
            "SERIAL-SECRET",
            "서울시",
            "보내는 사람",
            "받는 사람",
            "authorId",
            "documents",
            "hasDocument",
            "cost")) assertFalse(response.body().contains(excluded), excluded);
  }

  @Test
  void publicationRejectsMixedProductAndDuplicateSourceRowsWithoutPartialData() throws Exception {
    var valid =
        historyEvent(709, 1L, EventType.REPAIR, EventStatus.VALID, "2025-01-01", null, List.of());
    var foreign =
        new Event(
            710,
            201,
            2L,
            EventType.REPAIR,
            LocalDate.parse("2025-01-01"),
            Instant.parse("2025-01-01T08:00:00Z"),
            EventStatus.VALID,
            new PublicText("다른 제품 비공개 내용", null, null, null, true),
            List.of());
    for (var rows : List.of(List.of(valid, foreign), List.of(valid, valid))) {
      PUBLICATION_HISTORY.set(new Snapshot(State.READY, false, rows));
      var response = publication(101, "1", "");
      assertEquals(503, response.statusCode());
      assertFalse(response.body().contains("다른 제품 비공개 내용"));
      assertFalse(response.body().contains("eventId"));
    }
  }

  @Test
  void publicationRefreshDoesNotChangeSavedContentVersionOrPhotoSelections() throws Exception {
    var saved = data(save(101, "1", "{\"title\":\"작성 중인 글\",\"selectedImageIds\":[303,301]}"));
    assertEquals(200, publication(101, "1", "").statusCode());
    assertEquals(200, publication(101, "1", "").statusCode());
    var reread = data(readDraft(101, "1"));
    assertEquals(saved.get("draftId"), reread.get("draftId"));
    assertEquals(saved.get("version"), reread.get("version"));
    assertEquals(saved.get("selectedImageIds"), reread.get("selectedImageIds"));
    assertEquals(saved.get("title"), reread.get("title"));
  }

  @Test
  void previewRequiresAnExistingSavedDraftButAllowsIncompleteContent() throws Exception {
    assertEquals(404, preview(101, 1, 0, "1", "").statusCode());
    assertEquals(0, countDrafts());
    var saved = data(save(101, "1", "{}"));
    long id = saved.get("draftId").asLong();
    var response = preview(101, id, 0, "1", "");
    assertEquals(200, response.statusCode());
    assertTrue(data(response).get("content").get("title").isNull());
    assertTrue(data(response).get("content").get("price").isNull());
    assertTrue(data(response).get("content").get("conditionGrade").isNull());
    assertEquals(0, data(response).get("images").size());
    assertEquals("UNAVAILABLE", data(response).get("history").get("status").asText());
    assertEquals(401, preview(101, id, 0, null, "").statusCode());
    for (String query : List.of("&page=-1", "&size=0", "&size=101", "&page=wrong")) {
      assertEquals(400, preview(101, id, 0, "1", query).statusCode());
    }
    assertEquals(400, preview(101, 0, 0, "1", "").statusCode());
    assertEquals(400, preview(101, id, -1, "1", "").statusCode());
    assertEquals(1, countDrafts());
  }

  @Test
  void previewUsesSavedContentAndSelectedPhotoOrderWithoutModifyingTheDraft() throws Exception {
    var saved =
        data(
            save(
                101,
                "1",
                """
        {"title":"실제 저장한 제목","description":"저장한 설명","price":1500000,
         "conditionGrade":4,"selectedImageIds":[303,301]}
        """));
    long id = saved.get("draftId").asLong();
    saved = data(readDraft(101, "1"));
    var response = preview(101, id, 0, "1", "");
    assertEquals(200, response.statusCode());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    var result = data(response);
    assertEquals(saved.get("draftId"), result.get("draftId"));
    assertEquals(saved.get("updatedAt"), result.get("savedAt"));
    assertEquals("실제 저장한 제목", result.get("content").get("title").asText());
    assertEquals(1500000, result.get("content").get("price").asInt());
    assertEquals(4, result.get("content").get("conditionGrade").asInt());
    assertEquals(303, result.get("images").get(0).get("imageId").asInt());
    assertEquals(301, result.get("images").get(1).get("imageId").asInt());
    assertEquals("Apple", result.get("product").get("manufacturer").asText());
    assertEquals(18, result.get("product").get("specifications").get("ramGb").asInt());
    for (String excluded :
        List.of(
            "authorId",
            "ownerId",
            "serialNumber",
            "storageKey",
            "warrantyTerms",
            "documents",
            "hasDocument",
            "cost")) {
      assertFalse(response.body().contains(excluded), excluded);
    }
    assertEquals(200, preview(101, id, 0, "1", "").statusCode());
    assertEquals(saved, data(readDraft(101, "1")));
    assertEquals(
        "PRIVATE", jdbc.queryForObject("SELECT visibility FROM listing_drafts", String.class));
  }

  @Test
  void previewAndItsImagesRequireBothTheAuthorAndTheCurrentOwner() throws Exception {
    long id = data(save(101, "1", "{\"selectedImageIds\":[301]}")).get("draftId").asLong();
    String image =
        "/api/v1/products/101/listing-draft/preview/images/301?draftId=" + id + "&version=0";
    assertEquals(404, preview(101, id, 0, "2", "").statusCode());
    assertEquals(404, send("GET", image, "2", null).statusCode());
    jdbc.update("UPDATE listing_drafts SET author_id = 2 WHERE id = ?", id);
    assertEquals(404, preview(101, id, 0, "1", "").statusCode());
    assertEquals(404, send("GET", image, "1", null).statusCode());
    jdbc.update("UPDATE listing_drafts SET author_id = 1 WHERE id = ?", id);
    jdbc.update("UPDATE products SET owner_id = 2 WHERE id = 101");
    for (String user : List.of("1", "2")) {
      assertEquals(404, preview(101, id, 0, user, "").statusCode());
      assertEquals(404, send("GET", image, user, null).statusCode());
    }
  }

  @Test
  void previewRejectsModifiedAndRecreatedDraftsRatherThanReturningDifferentContent()
      throws Exception {
    long id =
        data(save(101, "1", "{\"title\":\"이전 내용\",\"selectedImageIds\":[301]}"))
            .get("draftId")
            .asLong();
    var changed = data(updateDraft(101, id, "1", "{\"title\":\"다른 탭에서 수정\",\"version\":0}"));
    assertEquals(409, preview(101, id, 0, "1", "").statusCode());
    assertEquals(
        409,
        send(
                "GET",
                "/api/v1/products/101/listing-draft/preview/images/301?draftId="
                    + id
                    + "&version=0",
                "1",
                null)
            .statusCode());
    assertEquals(200, preview(101, id, changed.get("version").asLong(), "1", "").statusCode());
    assertEquals(204, deleteDraft(101, id, "1", changed.get("version").asLong()).statusCode());
    long replacement = data(save(101, "1", "{}")).get("draftId").asLong();
    assertNotEquals(id, replacement);
    assertEquals(409, preview(101, id, 0, "1", "").statusCode());
    assertEquals(200, preview(101, replacement, 0, "1", "").statusCode());
  }

  @Test
  void previewOnlyServesSelectedPhotosAndNeverAnUnselectedOrForeignPhoto() throws Exception {
    long id = data(save(101, "1", "{\"selectedImageIds\":[301]}")).get("draftId").asLong();
    String suffix = "?draftId=" + id + "&version=0";
    var response =
        send("GET", "/api/v1/products/101/listing-draft/preview/images/301" + suffix, "1", null);
    assertEquals(200, response.statusCode());
    assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    for (long imageId : List.of(302L, 399L, 401L, 999L)) {
      assertEquals(
          404,
          send(
                  "GET",
                  "/api/v1/products/101/listing-draft/preview/images/" + imageId + suffix,
                  "1",
                  null)
              .statusCode());
    }
    assertEquals(
        401,
        send("GET", "/api/v1/products/101/listing-draft/preview/images/301" + suffix, null, null)
            .statusCode());
  }

  @Test
  void previewKeepsAllHistoryStatesAndPaginationWhileHidingUnreviewedText() throws Exception {
    long id = data(save(101, "1", "{}")).get("draftId").asLong();
    var previousOwner =
        historyEvent(
            720,
            2L,
            EventType.REPAIR,
            EventStatus.VOID,
            "2025-03-01",
            new PublicText("이메일 비공개", "a@private.example", null, null, false),
            List.of(new Correction(920, Instant.parse("2025-03-02T08:00:00Z"), "중복 이력 정정", true)));
    var currentOwner =
        historyEvent(
            721,
            1L,
            EventType.INSPECTION,
            EventStatus.VALID,
            "2025-04-01",
            new PublicText("정기 점검", "점검 완료", null, null, true),
            List.of());
    PUBLICATION_HISTORY.set(new Snapshot(State.READY, false, List.of(previousOwner, currentOwner)));
    var first = data(preview(101, id, 0, "1", "&size=1")).get("history");
    assertEquals(721, first.get("items").get(0).get("eventId").asInt());
    assertTrue(first.get("page").get("hasNext").asBoolean());
    var secondResponse = preview(101, id, 0, "1", "&page=1&size=1");
    var second = data(secondResponse).get("history");
    assertEquals(720, second.get("items").get(0).get("eventId").asInt());
    assertEquals("VOID", second.get("items").get(0).get("status").asText());
    assertTrue(second.get("items").get(0).get("previousOwner").asBoolean());
    assertEquals(
        "중복 이력 정정", second.get("items").get(0).get("corrections").get(0).get("reason").asText());
    assertFalse(secondResponse.body().contains("private.example"));
    assertEquals(1, second.get("validCount").asInt());
    assertEquals(1, second.get("voidCount").asInt());
    assertFalse(second.get("page").get("hasNext").asBoolean());
    assertEquals(0, data(readDraft(101, "1")).get("version").asLong());
  }

  @Test
  void aHistoryFailureDuringPreviewDoesNotDeleteOrChangeTheSavedDraft() throws Exception {
    var saved = data(save(101, "1", "{\"title\":\"저장 완료한 글\"}"));
    long id = saved.get("draftId").asLong();
    saved = data(readDraft(101, "1"));
    var event =
        historyEvent(722, 1L, EventType.REPAIR, EventStatus.VALID, "2025-01-01", null, List.of());
    PUBLICATION_HISTORY.set(new Snapshot(State.READY, false, List.of(event, event)));
    assertEquals(503, preview(101, id, 0, "1", "").statusCode());
    assertEquals(saved, data(readDraft(101, "1")));
  }

  @Test
  void publishAtomicallyCreatesTheListingRevealsHistoryAndRemovesOnlyTheUsedDraft()
      throws Exception {
    var saved = savePublishableDraft();
    save(102, "1", "{\"title\":\"보존할 다른 제품 초안\"}");
    var response = publish(saved, "1");
    assertEquals(201, response.statusCode(), response.body());
    long id = data(response).get("listingId").asLong();
    assertEquals("/api/v1/listings/" + id, response.headers().firstValue("Location").orElseThrow());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
    assertEquals("ACTIVE", data(response).get("status").asText());
    assertEquals("/?listingId=" + id, data(response).get("url").asText());
    assertEquals(404, readDraft(101, "1").statusCode());
    assertEquals(200, readDraft(102, "1").statusCode());
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM draft_images WHERE draft_id = ?",
            Integer.class,
            saved.get("draftId").asLong()));
    assertEquals(
        List.of(303L, 301L),
        jdbc.queryForList(
            "SELECT product_image_id FROM listing_images WHERE listing_id = ? ORDER BY sort_order",
            Long.class,
            id));
    assertEquals(
        303L,
        jdbc.queryForObject(
            "SELECT product_image_id FROM listing_images WHERE listing_id = ? AND is_primary = TRUE",
            Long.class,
            id));
    assertEquals("PUBLIC", historyVisibility());
    assertEquals(1, listingCount());
    var browse = send("GET", "/api/v1/listings?manufacturer=apple&cpu=m3", null, null);
    assertEquals(200, browse.statusCode(), browse.body());
    assertEquals(1, data(browse).get("page").get("totalElements").asInt());
    var summary = data(browse).get("items").get(0);
    assertEquals(id, summary.get("listingId").asLong());
    assertEquals("게시할 노트북", summary.get("title").asText());
    assertEquals("/api/v1/listings/" + id + "/thumbnail", summary.get("thumbnailUrl").asText());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action = 'LISTING_PUBLISHED' AND target_id = ? AND actor_id = 1",
            Integer.class,
            id));
  }

  @Test
  void publishingRequiresAllFinalFieldsWhileAnIncompleteDraftIsPreserved() throws Exception {
    var saved = data(save(101, "1", "{}"));
    PUBLICATION_HISTORY.set(new Snapshot(State.READY, false, List.of()));
    assertEquals(400, publish(saved, "1").statusCode());
    assertEquals(200, readDraft(101, "1").statusCode());
    for (String field :
        List.of("title", "description", "price", "conditionGrade", "selectedImageIds")) {
      var fields = new java.util.HashMap<String, Object>();
      fields.put("title", "게시 제목");
      fields.put("description", "게시 설명");
      fields.put("price", 1200000);
      fields.put("conditionGrade", 4);
      fields.put("selectedImageIds", List.of(301));
      fields.remove(field);
      fields.put("version", data(readDraft(101, "1")).get("version").asLong());
      saved = data(save(101, "1", JSON.writeValueAsString(fields)));
      var response = publish(saved, "1");
      assertEquals(400, response.statusCode(), field);
      assertTrue(response.body().contains("SALE_001"));
    }
    assertEquals(0, listingCount());
    assertEquals("PRIVATE", historyVisibility());
  }

  @Test
  void publishRejectsUnauthenticatedForeignAuthorAndStaleDraftRequests() throws Exception {
    var saved = savePublishableDraft();
    // 인증 헤더 없는 쓰기는 공통 CSRF 필터에서 먼저 거부한다.
    assertEquals(403, publish(saved, null).statusCode());
    assertEquals(401, publish(saved, "not-a-user-id").statusCode());
    assertEquals(404, publish(saved, "2").statusCode());
    long id = saved.get("draftId").asLong();
    assertEquals(
        409,
        send(
                "POST",
                "/api/v1/products/101/listings",
                "1",
                "{\"draftId\":" + id + ",\"version\":99}")
            .statusCode());
    jdbc.update("UPDATE listing_drafts SET author_id = 2 WHERE id = ?", id);
    assertEquals(404, publish(saved, "1").statusCode());
    assertEquals(0, listingCount());
    assertEquals("PRIVATE", historyVisibility());
  }

  @Test
  void publishValidatesItsReferenceBody() throws Exception {
    savePublishableDraft();
    for (String body :
        List.of(
            "{}",
            "{\"draftId\":0,\"version\":0}",
            "{\"draftId\":1,\"version\":-1}",
            "{\"draftId\":1}")) {
      assertEquals(400, send("POST", "/api/v1/products/101/listings", "1", body).statusCode());
    }
    assertEquals(0, listingCount());
  }

  @Test
  void publishBlocksOngoingTradesPendingTransfersAndUnavailableChecks() throws Exception {
    var saved = savePublishableDraft();
    for (var state :
        List.of(
            new ListingPublishConstraints.Snapshot(true, true, false),
            new ListingPublishConstraints.Snapshot(true, false, true))) {
      PUBLISH_CONSTRAINTS.set(state);
      var response = publish(saved, "1");
      assertEquals(409, response.statusCode());
      assertTrue(response.body().contains("SALE_003"));
    }
    PUBLISH_CONSTRAINTS.set(new ListingPublishConstraints.Snapshot(false, false, false));
    assertEquals(503, publish(saved, "1").statusCode());
    PUBLISH_CONSTRAINTS.set(null);
    assertEquals(503, publish(saved, "1").statusCode());
    assertEquals(0, listingCount());
    assertEquals("PRIVATE", historyVisibility());
    assertEquals(200, readDraft(101, "1").statusCode());
  }

  @Test
  void unavailableOrInvalidHistoryCannotBePublishedAsAnEmptyTimeline() throws Exception {
    var saved = savePublishableDraft();
    PUBLICATION_HISTORY.set(Snapshot.unavailable());
    assertEquals(503, publish(saved, "1").statusCode());
    var event =
        historyEvent(740, 1L, EventType.REPAIR, EventStatus.VALID, "2025-01-01", null, List.of());
    PUBLICATION_HISTORY.set(new Snapshot(State.READY, false, List.of(event, event)));
    assertEquals(503, publish(saved, "1").statusCode());
    assertEquals(0, listingCount());
    assertEquals("PRIVATE", historyVisibility());
  }

  @Test
  void retryingTheSamePublishReturnsTheExistingResultWithoutRepeatingSideEffects()
      throws Exception {
    var saved = savePublishableDraft();
    var first = publish(saved, "1");
    var retry = publish(saved, "1");
    assertEquals(201, first.statusCode());
    assertEquals(200, retry.statusCode());
    assertEquals(data(first).get("listingId"), data(retry).get("listingId"));
    assertEquals(1, listingCount());
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT publication_count FROM listing_history_publication_probe WHERE product_id=101",
            Integer.class));
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE action='LISTING_PUBLISHED'", Integer.class));
  }

  @Test
  void concurrentPublishesOfOneDraftProduceOnlyOneActiveListing() throws Exception {
    var saved = savePublishableDraft();
    java.util.function.Supplier<HttpResponse<String>> attempt =
        () -> {
          try {
            return publish(saved, "1");
          } catch (Exception failure) {
            throw new java.util.concurrent.CompletionException(failure);
          }
        };
    var first = CompletableFuture.supplyAsync(attempt);
    var second = CompletableFuture.supplyAsync(attempt);
    var responses = List.of(first.get(), second.get());
    assertEquals(
        List.of(200, 201), responses.stream().map(HttpResponse::statusCode).sorted().toList());
    assertEquals(data(responses.get(0)).get("listingId"), data(responses.get(1)).get("listingId"));
    assertEquals(1, listingCount());
    assertEquals(0, countDrafts());
  }

  @Test
  void aNewDraftCannotBypassAnExistingActiveOrReservedListing() throws Exception {
    var original = savePublishableDraft();
    long listingId = data(publish(original, "1")).get("listingId").asLong();
    var next = savePublishableDraft();
    var savedNext = data(readDraft(101, "1"));
    for (String status : List.of("ACTIVE", "RESERVED")) {
      jdbc.update("UPDATE listings SET status=? WHERE id=?", status, listingId);
      var composition = send("GET", "/api/v1/products/101/listing-draft/composition", "1", null);
      assertEquals(409, composition.statusCode(), status);
      assertEquals("SALE_002", JSON.readTree(composition.body()).get("code").asText());
      var blockedPreview =
          preview(101, next.get("draftId").asLong(), next.get("version").asLong(), "1", "");
      assertEquals(409, blockedPreview.statusCode(), status);
      assertEquals("SALE_002", JSON.readTree(blockedPreview.body()).get("code").asText());
      assertEquals(409, publish(next, "1").statusCode(), status);
      // 다른 사용자는 판매 상태를 알아내기 전에 기존 소유권 검사로 거부한다.
      assertEquals(
          404,
          send("GET", "/api/v1/products/101/listing-draft/composition", "2", null).statusCode());
      assertEquals(savedNext, data(readDraft(101, "1")));
    }
    assertEquals(1, listingCount());
    assertEquals(200, readDraft(101, "1").statusCode());
    for (String status : List.of("SOLD", "CANCELLED")) {
      jdbc.update("UPDATE listings SET status=?, active_slot=NULL WHERE id=?", status, listingId);
      assertEquals(
          200,
          send("GET", "/api/v1/products/101/listing-draft/composition", "1", null).statusCode());
      assertEquals(
          200,
          preview(101, next.get("draftId").asLong(), next.get("version").asLong(), "1", "")
              .statusCode());
    }
    assertEquals(201, publish(next, "1").statusCode());
    assertEquals(2, listingCount());
  }

  @Test
  void historyPublicationFailureRollsBackTheListingAndKeepsTheEntireDraft() throws Exception {
    var saved = savePublishableDraft();
    var before = data(readDraft(101, "1"));
    FAIL_HISTORY_PUBLICATION.set(true);
    assertEquals(503, publish(saved, "1").statusCode());
    assertEquals(0, listingCount());
    assertEquals("PRIVATE", historyVisibility());
    assertEquals(before, data(readDraft(101, "1")));
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM listing_images", Integer.class));
  }

  @Test
  void aFailureAfterDraftDeletionAlsoRestoresHistoryAndDraftPhotos() throws Exception {
    var saved = savePublishableDraft();
    var before = data(readDraft(101, "1"));
    jdbc.execute(
        "ALTER TABLE audit_logs ADD CONSTRAINT listing_publish_test_failure CHECK (action <> 'LISTING_PUBLISHED')");
    try {
      assertEquals(500, publish(saved, "1").statusCode());
      assertEquals(0, listingCount());
      assertEquals("PRIVATE", historyVisibility());
      assertEquals(before, data(readDraft(101, "1")));
    } finally {
      jdbc.execute("ALTER TABLE audit_logs DROP CONSTRAINT listing_publish_test_failure");
    }
  }

  @Test
  void foreignSelectedPhotosAreRejectedBeforeAnyPublicationSideEffect() throws Exception {
    var saved = savePublishableDraft();
    jdbc.update(
        "UPDATE draft_images SET product_image_id=401 WHERE draft_id=? AND product_image_id=303",
        saved.get("draftId").asLong());
    assertEquals(409, publish(saved, "1").statusCode());
    assertEquals(0, listingCount());
    assertEquals("PRIVATE", historyVisibility());
  }

  @Test
  void publishedDetailsAndPhotosAreReadableByOtherMembersButNotAnonymousVisitors()
      throws Exception {
    long id = data(publish(savePublishableDraft(), "1")).get("listingId").asLong();
    var response = send("GET", "/api/v1/listings/" + id, "2", null);
    assertEquals(200, response.statusCode());
    var detail = data(response);
    assertEquals("게시할 노트북", detail.get("content").get("title").asText());
    assertEquals(303, detail.get("images").get(0).get("imageId").asInt());
    for (String excluded :
        List.of(
            "draftId",
            "authorId",
            "sellerId",
            "ownerId",
            "serialNumber",
            "storageKey",
            "documents",
            "hasDocument",
            "warrantyTerms")) assertFalse(response.body().contains(excluded), excluded);
    assertEquals(401, send("GET", "/api/v1/listings/" + id, null, null).statusCode());
    assertEquals(400, send("GET", "/api/v1/listings/" + id + "?size=101", "2", null).statusCode());
    assertEquals(
        200, send("GET", "/api/v1/listings/" + id + "/images/301", "2", null).statusCode());
    assertEquals(
        404, send("GET", "/api/v1/listings/" + id + "/images/302", "2", null).statusCode());
    assertEquals(
        401, send("GET", "/api/v1/listings/" + id + "/images/301", null, null).statusCode());
  }

  @Test
  void reservedDetailsStayReadableButEndedOrTransferredListingsAreNotPublic() throws Exception {
    long id = data(publish(savePublishableDraft(), "1")).get("listingId").asLong();
    String path = "/api/v1/listings/" + id;
    jdbc.update("UPDATE listings SET status='RESERVED' WHERE id=?", id);
    assertEquals(200, send("GET", path, "2", null).statusCode());
    for (String state : List.of("SOLD", "CANCELLED")) {
      jdbc.update("UPDATE listings SET status=?, active_slot=NULL WHERE id=?", state, id);
      assertEquals(404, send("GET", path, "2", null).statusCode());
      assertEquals(404, send("GET", path + "/images/301", "2", null).statusCode());
    }
    jdbc.update("UPDATE listings SET status='ACTIVE', active_slot=1 WHERE id=?", id);
    jdbc.update("UPDATE products SET owner_id=2 WHERE id=101");
    assertEquals(404, send("GET", path, "1", null).statusCode());
    assertEquals(404, send("GET", path + "/images/301", "2", null).statusCode());
  }

  @Test
  void publishedImagesCannotBeDeletedOutFromUnderTheListing() throws Exception {
    publish(savePublishableDraft(), "1");
    var response = send("DELETE", "/api/v1/products/101/images/301", "1", null);
    assertEquals(409, response.statusCode());
    assertTrue(response.body().contains("PRODUCT_005"));
    assertEquals(
        0,
        jdbc.queryForObject(
            "SELECT count(*) FROM product_images WHERE id=301 AND deleted_at IS NOT NULL",
            Integer.class));
  }

  @Test
  void chatReadsThePublishedListingInsteadOfPrivateDrafts() throws Exception {
    var saved = savePublishableDraft();
    var reader = context.getBean(ChatListingReader.class);
    assertTrue(reader.findById(saved.get("draftId").asLong()).isEmpty());
    long id = data(publish(saved, "1")).get("listingId").asLong();
    var listing = reader.findById(id).orElseThrow();
    assertEquals("게시할 노트북", listing.title());
    assertEquals(1, listing.sellerId());
    jdbc.update("UPDATE listings SET status='RESERVED' WHERE id=?", id);
    assertEquals(
        org.example.springtestci.chat.domain.ChatListingStatus.RESERVED,
        reader.findById(id).orElseThrow().status());
  }

  @Test
  void databaseRejectsTwoOpenListingsAndAnInvalidOpenSlot() throws Exception {
    long id = data(publish(savePublishableDraft(), "1")).get("listingId").asLong();
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "INSERT INTO listings(product_id,seller_id,title,price,condition_grade,description,status,content_version,published_at,source_draft_id,source_draft_version,active_slot) SELECT product_id,seller_id,title,price,condition_grade,description,'RESERVED',0,CURRENT_TIMESTAMP,source_draft_id+10000,0,1 FROM listings WHERE id=?",
                id));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> jdbc.update("UPDATE listings SET active_slot=NULL WHERE id=?", id));
    assertEquals(1, listingCount());
  }

  private static JsonNode savePublishableDraft() throws Exception {
    PUBLICATION_HISTORY.set(new Snapshot(State.READY, false, List.of()));
    return data(
        save(
            101,
            "1",
            "{\"title\":\"게시할 노트북\",\"description\":\"게시할 설명\",\"price\":1500000,\"conditionGrade\":4,\"selectedImageIds\":[303,301]}"));
  }

  private static HttpResponse<String> publish(JsonNode draft, String user) throws Exception {
    return send(
        "POST",
        "/api/v1/products/101/listings",
        user,
        "{\"draftId\":"
            + draft.get("draftId").asLong()
            + ",\"version\":"
            + draft.get("version").asLong()
            + "}");
  }

  private static int listingCount() {
    return jdbc.queryForObject("SELECT count(*) FROM listings", Integer.class);
  }

  private static String historyVisibility() {
    return jdbc.queryForObject(
        "SELECT visibility FROM listing_history_publication_probe WHERE product_id=101",
        String.class);
  }

  private static HttpResponse<String> preview(
      long productId, long draftId, long version, String user, String query) throws Exception {
    return send(
        "GET",
        "/api/v1/products/"
            + productId
            + "/listing-draft/preview?draftId="
            + draftId
            + "&version="
            + version
            + query,
        user,
        null);
  }

  private static Event historyEvent(
      long id,
      Long authorId,
      EventType type,
      EventStatus status,
      String occurredOn,
      PublicText text,
      List<Correction> corrections) {
    return new Event(
        id,
        101,
        authorId,
        type,
        LocalDate.parse(occurredOn),
        Instant.parse("2025-04-01T08:00:00Z"),
        status,
        text,
        corrections);
  }

  private static HttpResponse<String> publication(long productId, String user, String query)
      throws Exception {
    return send(
        "GET", "/api/v1/products/" + productId + "/listing-draft/publication" + query, user, null);
  }

  private static int countDrafts() {
    return jdbc.queryForObject("SELECT count(*) FROM listing_drafts", Integer.class);
  }

  private static JsonNode data(HttpResponse<String> response) {
    return JSON.readTree(response.body()).get("data");
  }

  private static HttpResponse<String> save(long productId, String user, String body)
      throws Exception {
    var suppliedVersion = JSON.readTree(body).get("version");
    if (suppliedVersion != null && !suppliedVersion.isNull()) {
      return updateDraft(productId, SAVED_DRAFT_IDS.getOrDefault(productId, 1L), user, body);
    }
    var response = send("POST", "/api/v1/products/" + productId + "/listing-draft", user, body);
    if (response.statusCode() == 201)
      SAVED_DRAFT_IDS.put(productId, data(response).get("draftId").asLong());
    return response;
  }

  private static HttpResponse<String> readDraft(long productId, String user) throws Exception {
    return send("GET", "/api/v1/products/" + productId + "/listing-draft", user, null);
  }

  private static HttpResponse<String> updateDraft(
      long productId, long draftId, String user, String body) throws Exception {
    return send(
        "PUT", "/api/v1/products/" + productId + "/listing-draft?draftId=" + draftId, user, body);
  }

  private static HttpResponse<String> deleteDraft(
      long productId, long draftId, String user, long version) throws Exception {
    return send(
        "DELETE",
        "/api/v1/products/"
            + productId
            + "/listing-draft?draftId="
            + draftId
            + "&version="
            + version,
        user,
        null);
  }

  private static HttpResponse<String> uploadPhoto(long productId) throws Exception {
    String boundary = "R01ProductPhotoBoundary";
    var body = new java.io.ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"laptop.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    try (var image =
        new ClassPathResource("static/images/hero-laptop-passport.png").getInputStream()) {
      body.write(image.readAllBytes());
    }
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/products/" + productId + "/images"))
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + token("1"))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static int uncheckedSave(String body) {
    try {
      return save(101, "1", body).statusCode();
    } catch (Exception exception) {
      throw new RuntimeException(exception);
    }
  }

  private static HttpResponse<String> send(String method, String path, String user, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15));
    if (user != null) request.header("Authorization", "Bearer " + token(user));
    if (body != null) request.header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** 쿠키와 CSRF 헤더를 직접 지정해 POST 요청을 보낸다. */
  private static HttpResponse<String> sendWithCookie(String path, String cookie, String csrfHeader)
      throws Exception {
    return sendWithCookie("POST", path, cookie, csrfHeader, "{}");
  }

  private static HttpResponse<String> sendWithCookie(
      String method, String path, String cookie, String csrfHeader, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15))
            .header("Cookie", cookie)
            .header("Content-Type", "application/json");
    if (csrfHeader != null) request.header("X-XSRF-TOKEN", csrfHeader);
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
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return content
        + "."
        + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
  }
}
