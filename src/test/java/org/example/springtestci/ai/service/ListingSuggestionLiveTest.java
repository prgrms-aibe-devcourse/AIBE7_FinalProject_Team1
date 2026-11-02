package org.example.springtestci.ai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.example.springtestci.common.error.CommonException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 명시적 실행에서만 합성 공개 제품으로 실제 Gemini 제안을 검사한다. 운영 DB·계정은 사용하지 않는다. */
@EnabledIfEnvironmentVariable(named = "AI_SUGGESTION_LIVE", matches = "true")
class ListingSuggestionLiveTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SOURCE =
      """
      {"product":{"productId":101,"manufacturer":"Apple","modelName":"MacBook Pro 14",
      "purchaseDate":"2024-02-12","warrantyExpiresAt":"2027-02-12",
      "specifications":{"cpu":"Apple M3 Pro","ramGb":18,"storageGb":512,"storageType":"SSD","os":"macOS"}},
      "history":[{"eventId":1,"type":"PURCHASE","occurredOn":"2024-02-12","status":"VALID","previousOwner":true,"corrected":false,"detailsReviewed":true,"title":"제품 구매","content":null,"replacedPart":null},
      {"eventId":2,"type":"INSPECTION","occurredOn":"2025-05-20","status":"VALID","previousOwner":false,"corrected":true,"detailsReviewed":true,"title":"정기 점검","content":"메모리와 저장장치 동작 점검 완료","replacedPart":null},
      {"eventId":3,"type":"REPAIR","occurredOn":"2025-04-02","status":"VOID","previousOwner":true,"corrected":true,"detailsReviewed":false,"title":null,"content":null,"replacedPart":null}]}
      """;

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void actualFirstSuggestionsMeetThePublicOutputContract() throws Exception {
    String key = System.getenv("AI_LIVE_API_KEY");
    assertNotNull(key, "AI_LIVE_API_KEY is required");
    assertFalse(key.isBlank(), "AI_LIVE_API_KEY is required");
    var properties =
        new AiListingProperties(
            true,
            key,
            System.getenv().getOrDefault("AI_LIVE_MODEL", "gemini-3.5-flash-lite"),
            Integer.parseInt(System.getenv().getOrDefault("AI_LIVE_TIMEOUT", "20")),
            3,
            30);
    var delegate =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    var http = mock(HttpClient.class);
    var metadata = new AtomicReference<Map<String, Object>>();
    when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenAnswer(
            invocation ->
                delegate
                    .sendAsync(
                        invocation.getArgument(0),
                        (HttpResponse.BodyHandler<byte[]>) invocation.getArgument(1))
                    .thenApply(
                        response -> {
                          var info = new LinkedHashMap<String, Object>();
                          info.put("httpStatus", response.statusCode());
                          try {
                            var envelope = JSON.readTree(response.body());
                            String finish = envelope.at("/candidates/0/finishReason").asString();
                            info.put(
                                "finishReason",
                                Set.of("STOP", "MAX_TOKENS", "SAFETY", "RECITATION")
                                        .contains(finish)
                                    ? finish
                                    : "OTHER");
                            var usage = envelope.path("usageMetadata");
                            for (String field :
                                List.of(
                                    "promptTokenCount",
                                    "candidatesTokenCount",
                                    "thoughtsTokenCount"))
                              if (usage.path(field).isNumber())
                                info.put(field, usage.get(field).asInt());
                          } catch (RuntimeException ignored) {
                            info.put("jsonEnvelope", false);
                          }
                          metadata.set(info);
                          return response;
                        }));
    var gateway =
        new GeminiListingSuggestionGateway(
            properties,
            http,
            URI.create("https://generativelanguage.googleapis.com/v1beta/models/"));
    var policy = new AiListingTextPolicy();
    List<Map<String, Object>> results = new ArrayList<>();
    boolean passed = true;
    try {
      for (int attempt = 1; attempt <= 3; attempt++) {
        if (attempt > 1) Thread.sleep(10000);
        var row = new LinkedHashMap<String, Object>();
        row.put("attempt", attempt);
        metadata.set(null);
        long start = System.nanoTime();
        ListingSuggestionGateway.Suggestion raw = null;
        try {
          raw = gateway.generate(SOURCE);
          row.put("rawSuggestion", raw); // 합성 자료의 결과만 로컬 보고서에 저장한다.
          var validated = policy.validateSuggestion(raw, SOURCE);
          row.put("validatedSuggestion", validated);
          row.put("outputNormalized", !raw.equals(validated));
          row.put("passed", true);
        } catch (CommonException failure) {
          passed = false;
          row.put("passed", false);
          row.put("code", failure.errorCode().code());
          if (raw != null) {
            var missing = numbers(raw.title() + "\n" + raw.description());
            missing.removeAll(numbers(SOURCE));
            row.put("unregisteredNumbers", missing);
            row.put("titleLength", raw.title().length());
            row.put("descriptionLength", raw.description().length());
            row.put("hasMultilineTitle", raw.title().contains("\n") || raw.title().contains("\r"));
          }
        } finally {
          row.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
          if (metadata.get() != null) row.put("provider", metadata.get());
          results.add(row);
        }
        var provider = metadata.get();
        if (provider != null
            && ((int) provider.get("httpStatus") == 429 || (int) provider.get("httpStatus") >= 500))
          break;
      }
    } finally {
      Path report = Path.of("build/reports/ai-suggestion-live/results.json");
      Files.createDirectories(report.getParent());
      Files.writeString(
          report,
          JSON.writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "model",
                      properties.model(),
                      "dataset",
                      "synthetic public product only",
                      "cases",
                      results)));
    }
    assertTrue(passed && results.size() == 3, "Inspect the local synthetic suggestion report");
  }

  private static Set<String> numbers(String value) {
    Set<String> numbers = new TreeSet<>();
    var match = Pattern.compile("\\d+(?:[.,]\\d+)*").matcher(value);
    while (match.find()) numbers.add(match.group());
    return numbers;
  }
}
