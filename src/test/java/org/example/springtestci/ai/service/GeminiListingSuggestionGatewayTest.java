package org.example.springtestci.ai.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.example.springtestci.common.error.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** 실제 외부 모델·비용 없이 로컬 HTTP 서버로 Gemini 전송·오류·전체 기한을 검증한다. */
class GeminiListingSuggestionGatewayTest {
  private HttpServer server;
  private java.util.concurrent.ExecutorService workers;
  private GeminiListingSuggestionGateway gateway;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> request = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();
  private final AtomicReference<String> key = new AtomicReference<>();
  private String response;
  private int status;
  private long delay;

  @BeforeEach
  void start() throws Exception {
    status = 200;
    delay = 0;
    response = envelope("{\"title\":\"Apple 노트북\",\"description\":\"등록된 제품 정보입니다.\"}", "STOP");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newCachedThreadPool();
    server.setExecutor(workers);
    server.createContext(
        "/models/",
        exchange -> {
          calls.incrementAndGet();
          path.set(exchange.getRequestURI().toString());
          key.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
          request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            if (delay > 0) Thread.sleep(delay);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    gateway =
        new GeminiListingSuggestionGateway(
            properties(true),
            HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/"));
  }

  @AfterEach
  void stop() {
    server.stop(0);
    workers.shutdownNow();
  }

  @Test
  void sendsOnlyServerSourceAndStrictSchemaWithKeyInHeader() {
    var result = gateway.generate("{\"product\":\"Apple\"}");
    assertEquals("Apple 노트북", result.title());
    assertEquals("/models/gemini-3.5-flash-lite:generateContent", path.get());
    assertEquals("fixture-not-a-real-key", key.get());
    var json = JsonMapper.builder().build().readTree(request.get());
    assertEquals("{\"product\":\"Apple\"}", json.at("/contents/0/parts/0/text").asString());
    assertEquals(
        "APPLICATION_JSON", json.at("/generationConfig/responseFormat/text/mimeType").asString());
    assertFalse(
        json.at("/generationConfig/responseFormat/text/schema/additionalProperties").asBoolean());
    assertFalse(json.has("tools"));
    assertFalse(json.has("cachedContent"));
    assertFalse(json.get("generationConfig").has("temperature"));
    assertFalse(json.get("generationConfig").has("topK"));
    assertFalse(json.get("generationConfig").has("topP"));
    var instructions = json.at("/systemInstruction/parts/0/text").asString();
    assertTrue(instructions.contains("{제조사} {모델명} 중고 노트북 판매"));
    assertTrue(instructions.contains("제조사 → 모델명 → 사양 → 구매일 → 보증 만료일 → 이력"));
    assertTrue(instructions.contains("개인정보·무효·미검토·분량 제한 규칙을 우선"));
    assertEquals(1, calls.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 500, 503})
  void providerErrorsAreSanitizedAndNeverRetried(int code) {
    status = code;
    response = "credential and raw upstream content";
    assertCode(ErrorCode.AI_UNAVAILABLE);
    assertEquals(1, calls.get());
  }

  @Test
  void providerRateLimitIncludesRetryAfter() {
    status = 429;
    var failure = assertThrows(RateLimitedException.class, () -> gateway.generate("{}"));
    assertEquals(ErrorCode.AI_RATE_LIMITED, failure.errorCode());
    assertEquals(60, failure.retryAfter().toSeconds());
    assertEquals(1, calls.get());
  }

  @ParameterizedTest
  @ValueSource(strings = {"MAX_TOKENS", "SAFETY", "RECITATION", ""})
  void incompleteOrBlockedResponsesAreRejected(String finish) {
    response = envelope("{\"title\":\"제목\",\"description\":\"설명\"}", finish);
    assertCode(ErrorCode.AI_INVALID_RESULT);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "[]",
        "null",
        "not-json",
        "{\"title\":\"제목\",\"description\":\"설명\"} {\"other\":true}",
        "{\"title\":1,\"description\":\"설명\"}",
        "{\"title\":\"제목\",\"description\":\"설명\",\"other\":true}",
        "{\"title\":\"앞\",\"title\":\"뒤\",\"description\":\"설명\"}"
      })
  void rejectsMalformedOrExtraFields(String text) {
    response = envelope(text, "STOP");
    assertCode(ErrorCode.AI_INVALID_RESULT);
  }

  @Test
  void doesNotAcceptToolCallsAsText() {
    response =
        "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"publish\"}}]}}]}";
    assertCode(ErrorCode.AI_INVALID_RESULT);
  }

  @Test
  void boundsProviderBody() {
    response = "x".repeat(70000);
    assertCode(ErrorCode.AI_UNAVAILABLE);
  }

  @Test
  void timeoutStopsWaitingWithoutRetry() {
    delay = 2500;
    long start = System.nanoTime();
    assertCode(ErrorCode.AI_TIMEOUT);
    assertTrue(System.nanoTime() - start < 2_300_000_000L);
    assertEquals(1, calls.get());
  }

  @Test
  void missingConfigurationDoesNotCallProvider() {
    gateway = new GeminiListingSuggestionGateway(properties(false));
    assertCode(ErrorCode.AI_UNAVAILABLE);
    assertEquals(0, calls.get());
    assertFalse(properties(true).toString().contains("fixture-not-a-real-key"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "your-ai-api-key", "sample-key", "invalid\nkey"})
  void emptyAndExampleKeysDoNotCallProvider(String value) {
    gateway =
        new GeminiListingSuggestionGateway(
            new AiListingProperties(true, value, "gemini-3.5-flash-lite", 1, 3, 30));
    assertCode(ErrorCode.AI_UNAVAILABLE);
    assertEquals(0, calls.get());
  }

  private void assertCode(ErrorCode code) {
    assertEquals(
        code, assertThrows(CommonException.class, () -> gateway.generate("{}")).errorCode());
  }

  private AiListingProperties properties(boolean enabled) {
    return new AiListingProperties(
        enabled, "fixture-not-a-real-key", "gemini-3.5-flash-lite", 1, 3, 30);
  }

  private static String envelope(String text, String finish) {
    var json = JsonMapper.builder().build();
    return "{\"candidates\":[{\"finishReason\":"
        + json.writeValueAsString(finish)
        + ",\"content\":{\"parts\":[{\"text\":"
        + json.writeValueAsString(text)
        + "}]}}]}";
  }
}
