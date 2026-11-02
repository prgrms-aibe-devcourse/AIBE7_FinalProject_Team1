package org.example.springtestci.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.common.storage.FileStorage;
import org.example.springtestci.document.service.DocumentService;
import org.example.springtestci.ownership.dto.AcceptTransferRequest;
import org.example.springtestci.ownership.dto.ApproveTransferRequest;
import org.example.springtestci.ownership.dto.CreateTransferRequest;
import org.example.springtestci.ownership.service.OwnershipTransferService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP·SecurityFilterChain·H2/Flyway·파일 저장소로 보강 계약을 검증한다. PostgreSQL 인수와 구분한다. */
class RemediationHttpTest {
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static String base;
  private static Path storageRoot;
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @BeforeAll
  static void start() throws Exception {
    storageRoot = Files.createTempDirectory("tx-own-remediation-");
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    context =
        app.run(
            "--spring.profiles.active=test",
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:remediation_http;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver",
            "--spring.datasource.username=sa",
            "--spring.datasource.password=",
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--app.jwt.secret=remediation-test-only-secret-at-least-32-bytes",
            "--app.storage.local-root=" + storageRoot,
            "--app.ai.listing.enabled=false",
            "--app.ai.summary.enabled=false");
    jdbc = context.getBean(JdbcTemplate.class);
    for (long userId : List.of(1L, 2L, 3L)) {
      jdbc.update(
          "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES (?,?,'fixture',?,'USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          userId,
          "fixture" + userId + "@example.invalid",
          "회원" + userId);
    }
    for (long productId : List.of(101L, 102L, 103L, 104L)) {
      jdbc.update(
          "INSERT INTO products(id,category,manufacturer,manufacturer_normalized,model_name,serial_number_fingerprint,title,owner_id,serial_number_ciphertext) VALUES (?,'LAPTOP','Maker','MAKER','Model',?,'제품',1,'fixture')",
          productId,
          "fixture" + productId);
    }
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() throws Exception {
    if (context != null) context.close();
    if (storageRoot != null) {
      try (var paths = Files.walk(storageRoot)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
          Files.deleteIfExists(path);
      }
    }
  }

  private static HttpResponse<String> get(String path, long userId) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(base + path));
    if (userId > 0) {
      var claims =
          JwtClaimsSet.builder()
              .subject(Long.toString(userId))
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(300))
              .build();
      String token =
          context
              .getBean(JwtEncoder.class)
              .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
              .getTokenValue();
      request.header("Authorization", "Bearer " + token);
    }
    return HTTP.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void verifyRequiresAuthenticationAndRecipientBeforeSummary() throws Exception {
    var transfer =
        context
            .getBean(OwnershipTransferService.class)
            .createTransfer(1L, new CreateTransferRequest(101L, 2L, null));
    String path = "/api/v1/ownership/transfers/verify?token=" + transfer.rawToken();
    assertThat(get(path, 0L).statusCode()).isEqualTo(401);
    var wrong = get(path, 3L);
    assertThat(wrong.statusCode()).isEqualTo(403);
    assertThat(wrong.body()).doesNotContain("productTitle", "fromUserNickname");
    var recipient = get(path, 2L);
    assertThat(recipient.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(recipient.body()).path("data").path("canAccept").asBoolean()).isTrue();
  }

  @Test
  void consentDoesNotTransferUntilOwnerApprovesAndDuplicateApprovalHasNoEffects() {
    var service = context.getBean(OwnershipTransferService.class);
    var transfer = service.createTransfer(1L, new CreateTransferRequest(102L, null, null));
    long version =
        jdbc.queryForObject("SELECT facts_version FROM products WHERE id=102", Long.class);
    service.acceptTransfer(2L, new AcceptTransferRequest(transfer.rawToken()));
    service.acceptTransfer(2L, new AcceptTransferRequest(transfer.rawToken()));
    assertThat(jdbc.queryForObject("SELECT owner_id FROM products WHERE id=102", Long.class))
        .isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_events WHERE product_id=102", Long.class))
        .isZero();
    var request = new ApproveTransferRequest(2L, version);
    var result = service.approveTransfer(1L, transfer.transferId(), request);
    assertThat(service.approveTransfer(1L, transfer.transferId(), request)).isEqualTo(result);
    assertThat(jdbc.queryForObject("SELECT owner_id FROM products WHERE id=102", Long.class))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_events WHERE product_id=102 AND event_type='TRANSFER'",
                Long.class))
        .isEqualTo(1L);
    assertThat(service.transferStatus(1L, transfer.transferId()).status().name())
        .isEqualTo("COMPLETED");
    assertThatThrownBy(() -> service.transferStatus(3L, transfer.transferId()))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  void actualFileGetRejectsOldOwnerOldVersionAndLegacySignedUrl() throws Exception {
    var storage = context.getBean(FileStorage.class);
    String key =
        storage.store(
            "documents/103", "pdf", new ByteArrayInputStream("%PDF-1.7 fixture".getBytes()));
    jdbc.update(
        "INSERT INTO documents(id,product_id,uploader_id,object_key,content_type,byte_size,version,mask_confirmed_at) VALUES (901,103,1,?,'application/pdf',16,1,CURRENT_TIMESTAMP)",
        key);
    var documents = context.getBean(DocumentService.class);
    String fileUrl = documents.createFileUrl(901L, 1L).url();
    var issuedUrl = get("/api/v1/documents/901/file-url", 1L);
    assertThat(issuedUrl.statusCode()).isEqualTo(200);
    assertThat(issuedUrl.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
    assertThat(issuedUrl.headers().firstValue("Referrer-Policy").orElse(""))
        .isEqualTo("no-referrer");
    var issuedData = JSON.readTree(issuedUrl.body()).path("data");
    assertThat(issuedData.path("url").asText()).startsWith("/api/v1/documents/901/file?");
    assertThat(Instant.parse(issuedData.path("expiresAt").asText())).isAfter(Instant.now());
    var downloaded = get(fileUrl, 1L);
    assertThat(downloaded.body()).startsWith("%PDF");
    assertThat(downloaded.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store");
    assertThat(get(fileUrl, 0L).statusCode()).isEqualTo(401);
    assertThat(get(fileUrl, 3L).body()).doesNotContain("%PDF");
    assertThat(
            get(storage.createTemporaryUrl(key, Instant.now().plusSeconds(300)), 0L).statusCode())
        .isEqualTo(401);
    var transfers = context.getBean(OwnershipTransferService.class);
    var transfer = transfers.createTransfer(1L, new CreateTransferRequest(103L, 2L, null));
    transfers.acceptTransfer(2L, new AcceptTransferRequest(transfer.rawToken()));
    transfers.approveTransfer(
        1L,
        transfer.transferId(),
        new ApproveTransferRequest(
            2L,
            jdbc.queryForObject("SELECT facts_version FROM products WHERE id=103", Long.class)));
    assertThat(get(fileUrl, 1L).body()).doesNotContain("%PDF");
    String newOwnerUrl = documents.createFileUrl(901L, 2L).url();
    assertThat(get(newOwnerUrl, 2L).statusCode()).isEqualTo(200);
    documents.replace(
        901L, 2L, true, "masked.pdf", "application/pdf", "%PDF-1.7 replaced".getBytes());
    assertThat(get(newOwnerUrl, 2L).statusCode()).isEqualTo(403);
    jdbc.update("UPDATE products SET owner_id=1 WHERE id=103");
    jdbc.update("UPDATE documents SET protected_at=CURRENT_TIMESTAMP WHERE id=901");
    assertThatThrownBy(() -> documents.delete(901L, 1L)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void productPaginationValidatesInputsThroughHttp() throws Exception {
    assertThat(get("/api/v1/products?page=-1", 1L).statusCode()).isEqualTo(400);
    assertThat(get("/api/v1/products?size=101", 1L).statusCode()).isEqualTo(400);
    var result = JSON.readTree(get("/api/v1/products?page=0&size=1", 1L).body()).path("data");
    assertThat(result.path("items").size()).isEqualTo(1);
    assertThat(result.path("page").path("hasNext").asBoolean()).isTrue();
  }
}
