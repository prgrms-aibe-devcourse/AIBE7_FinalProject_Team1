package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.example.springtestci.listing.service.ListingHistoryReader.Snapshot;
import org.example.springtestci.listing.service.ListingHistoryReader.State;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 공개 목록과 회원 상세를 실제 HTTP·보안 설정·마이그레이션·DB로 검증한다. */
class ListingBrowseHttpTest {
  private static final String SECRET = "r06-test-only-signing-secret-at-least-32-bytes";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final AtomicInteger HISTORY_READS = new AtomicInteger();
  private static final AtomicReference<State> HISTORY_STATE = new AtomicReference<>(State.READY);
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static String baseUrl;
  private static String schema;
  private static Path storageRoot;

  @TestConfiguration(proxyBeanMethods = false)
  static class HistoryFixture {
    @Bean
    ListingHistoryReader listingHistoryBrowseFixture() {
      return productId -> {
        HISTORY_READS.incrementAndGet();
        return new Snapshot(HISTORY_STATE.get(), false, List.of());
      };
    }
  }

  @BeforeAll
  static void start() throws Exception {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    boolean postgres = url != null && !url.isBlank();
    if (postgres && !url.contains("sale_ai_test"))
      throw new IllegalStateException("Use a dedicated sale_ai_test database only");
    schema = postgres ? "listing_browse_r06_" + Long.toUnsignedString(System.nanoTime()) : "public";
    if (postgres) url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    else
      url =
          "jdbc:h2:mem:listing_browse_r06;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    storageRoot = Files.createTempDirectory("listing-browse-http-test-");
    for (String name : List.of("front", "side", "back", "thinkpad", "other")) {
      try (var image =
          new ClassPathResource("static/images/hero-laptop-passport.png").getInputStream()) {
        Files.copy(image, storageRoot.resolve("draft-demo-" + name + ".png"));
      }
    }
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.addPrimarySources(List.of(HistoryFixture.class));
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
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES (1,'r06-owner1@fixture.invalid','not-a-login-hash','r06-owner1','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'r06-owner2@fixture.invalid','not-a-login-hash','r06-owner2','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) {
      if (!"public".equals(schema)) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
      context.close();
    }
    if (storageRoot != null) {
      Path expectedParent =
          Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
      if (!storageRoot.toAbsolutePath().normalize().getParent().equals(expectedParent)
          || !storageRoot.getFileName().toString().startsWith("listing-browse-http-test-"))
        throw new IllegalStateException("Unexpected test cleanup directory");
      try (var paths = Files.walk(storageRoot)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM listing_images");
    jdbc.update("DELETE FROM listings");
    jdbc.update("DELETE FROM draft_images");
    jdbc.update("DELETE FROM listing_drafts");
    jdbc.update(
        "UPDATE products SET owner_id=1,archived_at=NULL,title='private-product-alias' WHERE id IN (101,102)");
    jdbc.update("UPDATE products SET owner_id=2,archived_at=NULL WHERE id=201");
    jdbc.update("UPDATE products SET owner_id=1,archived_at=CURRENT_TIMESTAMP WHERE id=103");
    jdbc.update("UPDATE product_images SET deleted_at=NULL WHERE id<>399");
    jdbc.update("UPDATE laptop_details SET gpu_spec=NULL WHERE product_id IN (101,102)");
    seed(11, 101, 1, 1500000, 4, "2026-10-20T09:00:00Z");
    seed(12, 102, 1, 900000, 5, "2026-10-20T10:00:00Z");
    seed(13, 201, 2, 700000, 3, "2026-10-20T11:00:00Z");
    seed(14, 103, 1, 300000, 2, "2026-10-20T12:00:00Z");
    jdbc.update(
        "INSERT INTO listing_images VALUES (11,303,0,TRUE),(11,301,1,FALSE),(12,304,0,TRUE),(13,401,0,TRUE)");
    jdbc.update(
        "INSERT INTO listing_drafts(product_id,author_id,title,description) VALUES (101,1,'private-draft-title','private-draft-description')");
    HISTORY_READS.set(0);
    HISTORY_STATE.set(State.READY);
  }

  @Test
  void anonymousListUsesOnlyPublishedSafeSummariesAndDoesNotNeedHistoryIntegration()
      throws Exception {
    HISTORY_STATE.set(State.UNAVAILABLE);
    var response = get("/api/v1/listings", null);
    assertEquals(200, response.statusCode());
    var data = data(response);
    assertEquals(List.of(13L, 12L, 11L), ids(data));
    assertEquals(3, data.get("page").get("totalElements").asInt());
    assertEquals(20, data.get("page").get("size").asInt());
    Set<String> expected =
        Set.of(
            "listingId",
            "title",
            "price",
            "conditionGrade",
            "publishedAt",
            "manufacturer",
            "modelName",
            "specifications",
            "thumbnailUrl");
    for (var item : data.get("items")) {
      var actual = new HashSet<String>();
      item.propertyNames().forEach(actual::add);
      assertEquals(expected, actual);
    }
    for (String forbidden :
        List.of(
            "private-product-alias",
            "private-draft-title",
            "private-draft-description",
            "private-description",
            "sellerId",
            "ownerId",
            "sourceDraft",
            "serial",
            "storageKey",
            "document",
            "history",
            "purchaseDate",
            "warranty")) {
      assertFalse(response.body().contains(forbidden), forbidden);
    }
    assertEquals(0, HISTORY_READS.get());
  }

  @Test
  void anonymousAccessIsLimitedToSummaryAndTheRepresentativeThumbnail() throws Exception {
    var image = getBytes("/api/v1/listings/11/thumbnail", null);
    assertEquals(200, image.statusCode());
    assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
    assertEquals("nosniff", image.headers().firstValue("X-Content-Type-Options").orElseThrow());
    assertTrue(image.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
    assertArrayEquals(Files.readAllBytes(storageRoot.resolve("draft-demo-back.png")), image.body());
    assertEquals(401, get("/api/v1/listings/11", null).statusCode());
    assertEquals(401, get("/api/v1/listings/11/images/301", null).statusCode());
    assertEquals(401, get("/api/v1/products/101/listing-draft/publication", null).statusCode());
    assertEquals(200, get("/api/v1/listings/11", "2").statusCode());
  }

  @Test
  void reservedClosedTransferredAndArchivedListingsAreFilteredBeforePagination() throws Exception {
    jdbc.update("UPDATE listings SET status='RESERVED' WHERE id=11");
    var page = data(get("/api/v1/listings?size=1", null));
    assertEquals(2, page.get("page").get("totalElements").asInt());
    assertEquals(List.of(13L), ids(page));
    assertEquals(200, get("/api/v1/listings/11", "2").statusCode());
    assertEquals(404, get("/api/v1/listings/11/thumbnail", null).statusCode());
    jdbc.update("UPDATE listings SET status='SOLD',active_slot=NULL WHERE id=12");
    jdbc.update("UPDATE products SET owner_id=1 WHERE id=201");
    var empty = data(get("/api/v1/listings?size=1", null));
    assertEquals(0, empty.get("page").get("totalElements").asInt());
    assertTrue(ids(empty).isEmpty());
    assertEquals(404, get("/api/v1/listings/12", "2").statusCode());
    assertEquals(404, get("/api/v1/listings/13", "1").statusCode());
  }

  @Test
  void allPublishedEndStatesRemoveTheSummaryThumbnailAndMemberDetail() throws Exception {
    for (String state : List.of("SOLD", "CANCELLED")) {
      jdbc.update("UPDATE listings SET status=?,active_slot=NULL WHERE id=11", state);
      assertFalse(ids(data(get("/api/v1/listings", null))).contains(11L));
      assertEquals(404, get("/api/v1/listings/11/thumbnail", null).statusCode());
      assertEquals(404, get("/api/v1/listings/11", "2").statusCode());
    }
  }

  @Test
  void combinesManufacturerPriceSpecificationsAndGradeAsAndConditions() throws Exception {
    jdbc.update("UPDATE laptop_details SET gpu_spec='NVIDIA RTX 4060' WHERE product_id=102");
    var exact =
        get(
            "/api/v1/listings?manufacturer=len-ovo&minPrice=900000&maxPrice=900000&cpu=CORE&gpu=rtx&minRamGb=32&minStorageGb=1024&storageType=SSD&os=WINDOWS&minConditionGrade=5",
            null);
    assertEquals(200, exact.statusCode(), exact.body());
    assertEquals(List.of(12L), ids(data(exact)));
    assertTrue(ids(data(get("/api/v1/listings?manufacturer=Apple&minRamGb=32", null))).isEmpty());
    assertTrue(ids(data(get("/api/v1/listings?minRamGb=33", null))).isEmpty());
    assertEquals(List.of(12L, 11L), ids(data(get("/api/v1/listings?minStorageGb=512", null))));
    assertEquals(List.of(11L), ids(data(get("/api/v1/listings?cpu=apple&os=macOS", null))));
    assertTrue(ids(data(get("/api/v1/listings?storageType=HDD", null))).isEmpty());
  }

  @Test
  void pagingAndSortsHaveStableIdTieBreakersAndRetainFilteredTotals() throws Exception {
    jdbc.update(
        "UPDATE listings SET published_at=? WHERE id IN (11,12,13)",
        java.sql.Timestamp.from(Instant.parse("2026-10-20T12:00:00Z")));
    assertEquals(List.of(13L, 12L, 11L), ids(data(get("/api/v1/listings", null))));
    assertEquals(List.of(13L, 12L, 11L), ids(data(get("/api/v1/listings?sort=PRICE_ASC", null))));
    assertEquals(List.of(11L, 12L, 13L), ids(data(get("/api/v1/listings?sort=PRICE_DESC", null))));
    jdbc.update("UPDATE listings SET price=700000 WHERE id=12");
    assertEquals(List.of(13L, 12L, 11L), ids(data(get("/api/v1/listings?sort=PRICE_ASC", null))));
    for (int number = 0; number < 3; number++) {
      var page = data(get("/api/v1/listings?size=1&page=" + number, null));
      assertEquals(List.of(13L - number), ids(page));
      assertEquals(3, page.get("page").get("totalElements").asInt());
      assertEquals(number < 2, page.get("page").get("hasNext").asBoolean());
    }
    var far = data(get("/api/v1/listings?page=99&size=1", null));
    assertTrue(ids(far).isEmpty());
    assertEquals(3, far.get("page").get("totalElements").asInt());
  }

  @Test
  void rejectsInvalidBoundsEnumsAndReversedPriceRanges() throws Exception {
    for (String query :
        List.of(
            "page=-1",
            "page=nope",
            "size=0",
            "size=101",
            "minPrice=-1",
            "maxPrice=-1",
            "minPrice=1.5",
            "minPrice=1000000000000",
            "minPrice=20&maxPrice=10",
            "minRamGb=0",
            "minRamGb=2.5",
            "minStorageGb=-1",
            "minStorageGb=2147483648",
            "minConditionGrade=0",
            "minConditionGrade=6",
            "storageType=USB",
            "sort=UNKNOWN",
            "manufacturer=" + "a".repeat(101),
            "cpu=" + "a".repeat(101),
            "os=" + "a".repeat(51))) {
      var response = get("/api/v1/listings?" + query, null);
      assertEquals(400, response.statusCode(), query + ": " + response.body());
    }
  }

  @Test
  void filterValuesAreLiteralParametersAndCannotOverrideTheActivePolicy() throws Exception {
    for (String text : List.of("%", "_", "' OR 1=1 --", "!")) {
      var response =
          get("/api/v1/listings?cpu=" + URLEncoder.encode(text, StandardCharsets.UTF_8), null);
      assertEquals(200, response.statusCode());
      assertTrue(ids(data(response)).isEmpty(), text);
    }
    jdbc.update("UPDATE listings SET status='RESERVED' WHERE id=11");
    assertEquals(
        List.of(13L, 12L), ids(data(get("/api/v1/listings?status=RESERVED&sellerId=1", null))));
    assertEquals(
        400,
        get(
                "/api/v1/listings?sort="
                    + URLEncoder.encode("price; DROP TABLE listings", StandardCharsets.UTF_8),
                null)
            .statusCode());
    assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM listings", Integer.class));
  }

  @Test
  void staleAccessCookiesDoNotBlockPublicReadsButStillCannotReadDetails() throws Exception {
    for (String path : List.of("/api/v1/listings", "/api/v1/listings/11/thumbnail")) {
      assertEquals(200, getWithHeader(path, "Cookie", "access_token=not-a-valid-jwt").statusCode());
    }
    assertEquals(
        401,
        getWithHeader("/api/v1/listings/11", "Cookie", "access_token=not-a-valid-jwt")
            .statusCode());
    assertEquals(
        401, getWithHeader("/api/v1/listings", "Authorization", "Bearer invalid").statusCode());
  }

  @Test
  void thumbnailUrlsRecheckCurrentOwnershipAndSelectedPhotoAvailability() throws Exception {
    jdbc.update("UPDATE product_images SET deleted_at=CURRENT_TIMESTAMP WHERE id=303");
    var list = data(get("/api/v1/listings", null));
    assertTrue(list.get("items").get(2).get("thumbnailUrl").isNull());
    assertEquals(404, get("/api/v1/listings/11/thumbnail", null).statusCode());
    jdbc.update("UPDATE product_images SET deleted_at=NULL WHERE id=303");
    jdbc.update("UPDATE products SET owner_id=2 WHERE id=101");
    assertEquals(404, get("/api/v1/listings/11/thumbnail", null).statusCode());
    assertFalse(ids(data(get("/api/v1/listings", null))).contains(11L));
  }

  @Test
  void foreignAndMissingPrimarySelectionsCannotExposeAnotherProductsPhoto() throws Exception {
    jdbc.update(
        "UPDATE listing_images SET product_image_id=401 WHERE listing_id=11 AND sort_order=0");
    assertTrue(
        data(get("/api/v1/listings", null)).get("items").get(2).get("thumbnailUrl").isNull());
    assertEquals(404, get("/api/v1/listings/11/thumbnail", null).statusCode());
    jdbc.update("DELETE FROM listing_images WHERE listing_id=11 AND sort_order=0");
    assertTrue(
        data(get("/api/v1/listings", null)).get("items").get(2).get("thumbnailUrl").isNull());
    assertEquals(404, get("/api/v1/listings/11/thumbnail", null).statusCode());
  }

  /** NFR-01: 판매글 목록과 상세 조회 API는 측정 요청의 95%가 1초 이내에 응답한다. */
  @Test
  void p95ResponseTimeUnderOneSecondForListingBrowse() throws Exception {
    int iterations = 30;
    long[] listTimes = new long[iterations];
    long[] detailTimes = new long[iterations];

    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      var response = get("/api/v1/listings?page=0&size=10", null);
      listTimes[i] = (System.nanoTime() - start) / 1_000_000;
      assertEquals(200, response.statusCode());

      start = System.nanoTime();
      var detail = get("/api/v1/listings/11", "1");
      detailTimes[i] = (System.nanoTime() - start) / 1_000_000;
      assertEquals(200, detail.statusCode());
    }

    java.util.Arrays.sort(listTimes);
    java.util.Arrays.sort(detailTimes);

    int p95Index = (int) Math.ceil(iterations * 0.95) - 1;
    long listP95 = listTimes[p95Index];
    long detailP95 = detailTimes[p95Index];

    assertTrue(listP95 < 1000, "Listing browse P95 response time must be under 1000ms: " + listP95);
    assertTrue(
        detailP95 < 1000, "Listing detail P95 response time must be under 1000ms: " + detailP95);
  }

  private void seed(long id, long product, long seller, int price, int grade, String date) {
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?,0,1)",
        id,
        product,
        seller,
        "published-" + id,
        price,
        grade,
        "private-description",
        java.sql.Timestamp.from(Instant.parse(date)),
        10000 + id);
  }

  private static List<Long> ids(JsonNode data) {
    var result = new java.util.ArrayList<Long>();
    data.get("items").forEach(item -> result.add(item.get("listingId").asLong()));
    return result;
  }

  private static JsonNode data(HttpResponse<String> response) {
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body()).get("data");
  }

  private static HttpResponse<String> get(String path, String user) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15)).GET();
    if (user != null) builder.header("Authorization", "Bearer " + token(user));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<byte[]> getBytes(String path, String user) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15)).GET();
    if (user != null) builder.header("Authorization", "Bearer " + token(user));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static HttpResponse<String> getWithHeader(String path, String name, String value)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(15))
            .header(name, value)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String user) throws Exception {
    var encoder = Base64.getUrlEncoder().withoutPadding();
    String header =
        encoder.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    long now = Instant.now().getEpochSecond();
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
