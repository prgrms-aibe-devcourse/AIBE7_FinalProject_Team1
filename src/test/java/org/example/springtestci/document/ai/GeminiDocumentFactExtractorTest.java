package org.example.springtestci.document.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.example.springtestci.document.entity.DocumentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GeminiDocumentFactExtractorTest {

  private HttpServer server;
  private int status;
  private String responseBody;
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
  private final AtomicReference<String> path = new AtomicReference<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requestBody.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
          path.set(exchange.getRequestURI().getPath());
          byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private GeminiDocumentFactExtractor extractor() {
    return new GeminiDocumentFactExtractor(
        new GeminiProperties(
            "test-key", "test-model", "http://127.0.0.1:" + server.getAddress().getPort(), 5));
  }

  private Map<String, Object> call() throws DocumentFactExtractionException {
    return extractor().extract(new byte[] {1, 2, 3}, "image/png", DocumentType.RECEIPT);
  }

  private String errorCode() {
    return assertThrows(DocumentFactExtractionException.class, this::call).errorCode();
  }

  @Test
  void parsesCandidateJsonAndSendsKeyInHeaderOnly() throws Exception {
    status = 200;
    responseBody =
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"productName\\\":\\\"gram\\\",\\\"price\\\":1000}\"}]}}]}";

    Map<String, Object> facts = call();

    assertEquals("gram", facts.get("productName"));
    assertEquals("test-key", apiKeyHeader.get());
    assertEquals("/v1beta/models/test-model:generateContent", path.get());
    assertTrue(requestBody.get().contains("\"inlineData\""));
    assertTrue(requestBody.get().contains("\"responseSchema\""));
    assertTrue(!requestBody.get().contains("test-key"));
    var mapper = new ObjectMapper();
    var request = mapper.readTree(requestBody.get());
    var config = request.get("generationConfig");
    var fields =
        List.of(
            "productName",
            "modelName",
            "purchaseDate",
            "vendor",
            "price",
            "warrantyMonths",
            "repairDate",
            "repairDescription");
    assertEquals(mapper.valueToTree(fields), config.at("/responseSchema/required"));
    assertEquals(fields.size(), config.at("/responseSchema/properties").size());
    for (String field : fields)
      assertTrue(config.at("/responseSchema/properties/" + field + "/nullable").asBoolean());
    for (String option : List.of("temperature", "topK", "topP")) assertFalse(config.has(option));
    var instructions = request.at("/systemInstruction/parts/0/text").asText();
    assertTrue(instructions.contains("확정할 수 없으면 빈 문자열·임의 값 대신 null"));
    assertTrue(instructions.contains("원래 순서대로"));
    assertTrue(instructions.contains("개인정보는 어떤 항목에도 포함하지 마세요"));
  }

  @Test
  void mapsHttpStatusToErrorCodes() {
    responseBody = "{}";
    status = 429;
    assertEquals("AI_RATE_LIMITED", errorCode());
    status = 503;
    assertEquals("AI_UNAVAILABLE", errorCode());
    status = 400;
    assertEquals("AI_REQUEST_REJECTED", errorCode());
  }

  @Test
  void detectsBlockedEmptyAndBadResponses() {
    status = 200;
    responseBody = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
    assertEquals("AI_BLOCKED", errorCode());
    responseBody = "{\"candidates\":[]}";
    assertEquals("AI_EMPTY_RESPONSE", errorCode());
    responseBody = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"not json\"}]}}]}";
    assertEquals("AI_BAD_RESPONSE", errorCode());
    responseBody = "<html>";
    assertEquals("AI_BAD_RESPONSE", errorCode());
  }

  @Test
  void connectionFailureIsUnavailable() {
    int port = server.getAddress().getPort();
    server.stop(0);
    var closed =
        new GeminiDocumentFactExtractor(
            new GeminiProperties("k", "m", "http://127.0.0.1:" + port, 2));

    var exception =
        assertThrows(
            DocumentFactExtractionException.class,
            () -> closed.extract(new byte[] {1}, "image/png", DocumentType.OTHER));

    assertEquals("AI_UNAVAILABLE", exception.errorCode());
  }
}
