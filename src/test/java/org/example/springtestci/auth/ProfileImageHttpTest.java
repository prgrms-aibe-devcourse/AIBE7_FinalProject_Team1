package org.example.springtestci.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import org.example.springtestci.SpringTestCiApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 실제 HTTP·JWT 쿠키·CSRF를 거쳐 프로필 사진 API가 본인 사진만 저장·조회·삭제하는지 확인한다. */
class ProfileImageHttpTest {

  private static final String SECRET = "profile-image-test-only-signing-key-at-least-32-bytes";
  private static final String PATH = "/api/v1/me/profile-image";
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static ConfigurableApplicationContext context;
  private static String base;

  @BeforeAll
  static void start() {
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    context =
        app.run(
            "--spring.profiles.active=test",
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:profile_image;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver",
            "--spring.datasource.username=sa",
            "--spring.datasource.password=",
            "--spring.flyway.enabled=true",
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--app.ai.summary.enabled=false",
            "--app.ai.listing.api-key=",
            "--app.jwt.secret=" + SECRET);
    context
        .getBean(JdbcTemplate.class)
        .update(
            "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES"
                + "(1,'pi-a@fixture.invalid','unused','pi-a','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                + "(2,'pi-b@fixture.invalid','unused','pi-b','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  @AfterAll
  static void stop() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void 저장한_사진은_본인만_받을_수_있고_256px_JPEG다() throws Exception {
    assertEquals(204, send("GET", "1", null, true).statusCode());

    assertEquals(204, put("1", png(600, 300)).statusCode());

    var mine = sendBytes("1");
    assertEquals(200, mine.statusCode());
    assertEquals("image/jpeg", mine.headers().firstValue("Content-Type").orElseThrow());
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(mine.body()));
    assertEquals(256, decoded.getWidth());
    assertEquals(256, decoded.getHeight());
    // 다른 회원에게는 내 사진이 보이지 않는다.
    assertEquals(204, send("GET", "2", null, true).statusCode());
  }

  @Test
  void 이미지가_아닌_파일은_400으로_거부한다() throws Exception {
    var response = put("1", "not an image".getBytes(StandardCharsets.UTF_8));

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("AUTH_013"), response.body());
  }

  @Test
  void 삭제하면_다시_사진이_없다() throws Exception {
    assertEquals(204, put("2", png(40, 40)).statusCode());

    assertEquals(204, send("DELETE", "2", null, true).statusCode());

    assertEquals(204, send("GET", "2", null, true).statusCode());
  }

  @Test
  void 로그인과_CSRF_토큰이_없으면_거부한다() throws Exception {
    assertEquals(401, send("GET", null, null, false).statusCode());
    assertEquals(403, put("1", png(40, 40), false).statusCode());
  }

  private static byte[] png(int width, int height) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
    return out.toByteArray();
  }

  private static HttpResponse<String> send(String method, String user, byte[] body, boolean csrf)
      throws Exception {
    return HTTP.send(request(method, user, body, csrf, null), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<byte[]> sendBytes(String user) throws Exception {
    return HTTP.send(
        request("GET", user, null, true, null), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static HttpResponse<String> put(String user, byte[] file) throws Exception {
    return put(user, file, true);
  }

  private static HttpResponse<String> put(String user, byte[] file, boolean csrf) throws Exception {
    String boundary = "----profile-image-test";
    var body = new ByteArrayOutputStream();
    body.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p.jpg\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    body.write(file);
    body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return HTTP.send(
        request("PUT", user, body.toByteArray(), csrf, "multipart/form-data; boundary=" + boundary),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpRequest request(
      String method, String user, byte[] body, boolean csrf, String contentType) throws Exception {
    var builder =
        HttpRequest.newBuilder(URI.create(base + PATH))
            .timeout(Duration.ofSeconds(20))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body));
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    // 브라우저와 같게 JWT는 쿠키로, CSRF 토큰은 쿠키와 헤더로 함께 보낸다.
    List<String> cookies = new java.util.ArrayList<>();
    if (user != null) {
      cookies.add("access_token=" + token(user));
    }
    if (csrf) {
      cookies.add("XSRF-TOKEN=pi-csrf");
      builder.header("X-XSRF-TOKEN", "pi-csrf");
    }
    if (!cookies.isEmpty()) {
      builder.header("Cookie", String.join("; ", cookies));
    }
    return builder.build();
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
