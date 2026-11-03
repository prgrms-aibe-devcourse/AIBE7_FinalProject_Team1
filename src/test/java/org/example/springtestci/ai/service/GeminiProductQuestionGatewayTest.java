package org.example.springtestci.ai.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.example.springtestci.common.error.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 Gemini 대신 로컬 HTTP 공급자로 두 왕복·함수 결과·서명 보존·전체 기한을 검증한다. */
class GeminiProductQuestionGatewayTest {
  private final JsonMapper json = JsonMapper.builder().build();
  private HttpServer server;
  private ExecutorService workers;
  private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private final List<String> invoked = new CopyOnWriteArrayList<>();
  private final AtomicInteger calls = new AtomicInteger();
  private String first, last;
  private int secondStatus;
  private long delay;

  @BeforeEach
  void start() throws Exception {
    secondStatus = 200;
    delay = 0;
    first =
        envelope(
            List.of(
                Map.of(
                    "functionCall",
                    Map.of("name", "get_product_specs", "args", Map.of(), "id", "call-1"),
                    "thoughtSignature",
                    "fixture-signature")));
    last =
        envelope(
            List.of(
                Map.of("text", "{\"answer\":\"등록된 메모리는 18GB입니다.\",\"sourceKeys\":[\"specs\"]}")));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    workers = Executors.newCachedThreadPool();
    server.setExecutor(workers);
    server.createContext(
        "/models/",
        exchange -> {
          int number = calls.incrementAndGet();
          requests.add(
              json.readTree(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
          assertEquals(
              "fixture-not-a-real-key", exchange.getRequestHeaders().getFirst("x-goog-api-key"));
          try {
            if (delay > 0) Thread.sleep(delay);
            byte[] result = (number == 1 ? first : last).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(number == 1 ? 200 : secondStatus, result.length);
            exchange.getResponseBody().write(result);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
    workers.shutdownNow();
  }

  private GeminiProductQuestionGateway gateway(int timeout) {
    var properties =
        new AiListingProperties(
            true, "fixture-not-a-real-key", "gemini-3.5-flash-lite", timeout, 3, 30);
    var client =
        new GeminiJsonClient(
            properties,
            HttpClient.newHttpClient(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/models/"));
    return new GeminiProductQuestionGateway(client, properties);
  }

  private Map<String, Object> read(String name) {
    invoked.add(name);
    return Map.of("sourceKey", "specs", "data", Map.of("ramGb", 18));
  }

  @Test
  void executesOnlyReadToolsAndPreservesThoughtSignatureAndFunctionCallId() {
    var answer = gateway(5).ask("메모리는?", this::read);
    assertEquals(List.of("get_product_specs"), invoked);
    assertEquals(List.of("specs"), answer.sourceKeys());
    assertEquals(2, calls.get());
    assertFalse(requests.get(0).toString().contains("ramGb"));
    assertEquals("ANY", requests.get(0).at("/toolConfig/functionCallingConfig/mode").asString());
    var finalRequest = requests.get(1);
    assertEquals(
        "fixture-signature", finalRequest.at("/contents/1/parts/0/thoughtSignature").asString());
    assertEquals("call-1", finalRequest.at("/contents/2/parts/0/functionResponse/id").asString());
    assertEquals(
        18, finalRequest.at("/contents/2/parts/0/functionResponse/response/data/ramGb").asInt());
    assertEquals("NONE", finalRequest.at("/toolConfig/functionCallingConfig/mode").asString());
    assertEquals(4, finalRequest.at("/tools/0/functionDeclarations").size());
    assertFalse(finalRequest.toString().contains("fixture-not-a-real-key"));
  }

  @Test
  void supportsIndependentParallelReadCallsWithoutAllowingExtraModelRounds() {
    first =
        envelope(
            List.of(
                Map.of("functionCall", Map.of("name", "get_product_specs", "args", Map.of())),
                Map.of("functionCall", Map.of("name", "get_product_history", "args", Map.of()))));
    gateway(5).ask("메모리와 교체 기록은?", this::read);
    assertEquals(List.of("get_product_specs", "get_product_history"), invoked);
    assertEquals(2, calls.get());
    assertEquals(2, requests.get(1).at("/contents/2/parts").size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"delete_product", "execute_sql", "get_product_specs_with_admin"})
  void rejectsUnknownAndWriteFunctionsBeforeExecution(String name) {
    first = envelope(List.of(Map.of("functionCall", Map.of("name", name, "args", Map.of()))));
    assertCode(ErrorCode.AI_INVALID_RESULT);
    assertTrue(invoked.isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void rejectsModelSuppliedIdentityOrAnyOtherArguments() {
    first =
        envelope(
            List.of(
                Map.of(
                    "functionCall",
                    Map.of(
                        "name",
                        "get_product_specs",
                        "args",
                        Map.of("productId", 201, "userId", 2)))));
    assertCode(ErrorCode.AI_INVALID_RESULT);
    assertTrue(invoked.isEmpty());
  }

  @Test
  void rejectsDuplicateCallsInsteadOfSpendingMoreRequests() {
    first =
        envelope(
            List.of(
                Map.of("functionCall", Map.of("name", "get_product_specs", "args", Map.of())),
                Map.of("functionCall", Map.of("name", "get_product_specs", "args", Map.of()))));
    assertCode(ErrorCode.AI_INVALID_RESULT);
    assertTrue(invoked.isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void unrelatedChoiceStopsBeforeReadingDataOrMakingASecondProviderRequest() {
    first =
        envelope(
            List.of(
                Map.of(
                    "functionCall",
                    Map.of("name", "decline_unrelated_question", "args", Map.of()))));
    var answer = gateway(5).ask("노트북으로 색다른 활동을 정해줘", this::read);
    assertEquals(ProductQuestionGateway.Scope.OUT_OF_SCOPE, answer.scope());
    assertTrue(answer.sourceKeys().isEmpty());
    assertTrue(invoked.isEmpty());
    assertEquals(1, calls.get());
    assertFalse(requests.get(0).toString().contains("ramGb"));
  }

  @Test
  void unrelatedChoiceTakesPrecedenceEvenIfTheModelAlsoSelectsProductTools() {
    first =
        envelope(
            List.of(
                Map.of("functionCall", Map.of("name", "get_product_specs", "args", Map.of())),
                Map.of(
                    "functionCall",
                    Map.of("name", "decline_unrelated_question", "args", Map.of()))));
    assertEquals(
        ProductQuestionGateway.Scope.OUT_OF_SCOPE,
        gateway(5).ask("메모리와 다른 주제", this::read).scope());
    assertTrue(invoked.isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void unrelatedChoiceCannotCarryArgumentsOrModelWrittenAnswers() {
    first =
        envelope(
            List.of(
                Map.of(
                    "functionCall",
                    Map.of(
                        "name", "decline_unrelated_question", "args", Map.of("answer", "임의 답변")))));
    assertCode(ErrorCode.AI_INVALID_RESULT);
    assertTrue(invoked.isEmpty());
    assertEquals(1, calls.get());
  }

  @Test
  void rejectsTextWithoutDataLookup() {
    first = envelope(List.of(Map.of("text", "확인하지 않고 답합니다.")));
    assertCode(ErrorCode.AI_INVALID_RESULT);
    assertTrue(invoked.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"answer\":\"test\",\"sourceKeys\":[501]}",
        "{\"answer\":\"test\",\"sourceKeys\":[],\"sql\":\"select *\"}",
        "{\"answer\":null,\"sourceKeys\":[]}"
      })
  void rejectsMalformedAnswerSchema(String answer) {
    last = envelope(List.of(Map.of("text", answer)));
    assertCode(ErrorCode.AI_INVALID_RESULT);
    assertEquals(2, calls.get());
  }

  @Test
  void rejectsAnotherFunctionCallInTheFinalAnswer() {
    last = first;
    assertCode(ErrorCode.AI_INVALID_RESULT);
    assertEquals(2, calls.get());
    assertEquals(1, invoked.size());
  }

  @Test
  void providerRateLimitIsNotAutomaticallyRetried() {
    secondStatus = 429;
    last = "private provider error";
    var error = assertThrows(RateLimitedException.class, () -> gateway(5).ask("메모리는?", this::read));
    assertEquals(ErrorCode.AI_RATE_LIMITED, error.errorCode());
    assertEquals(2, calls.get());
  }

  @Test
  void twoCallsShareOneDeadline() {
    delay = 650;
    long before = System.nanoTime();
    var error = assertThrows(CommonException.class, () -> gateway(1).ask("메모리는?", this::read));
    assertEquals(ErrorCode.AI_TIMEOUT, error.errorCode());
    assertTrue(Duration.ofNanos(System.nanoTime() - before).toMillis() < 2000);
    assertEquals(2, calls.get());
  }

  private String envelope(List<Map<String, Object>> parts) {
    return json.writeValueAsString(
        Map.of(
            "candidates",
            List.of(
                Map.of(
                    "finishReason", "STOP", "content", Map.of("role", "model", "parts", parts)))));
  }

  private void assertCode(ErrorCode code) {
    var error = assertThrows(CommonException.class, () -> gateway(5).ask("메모리는?", this::read));
    assertEquals(code, error.errorCode());
  }
}
