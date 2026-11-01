package org.example.springtestci.ai.service;

import static org.junit.jupiter.api.Assertions.*;

import org.example.springtestci.common.error.CommonException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiListingTextPolicyTest {
  private final AiListingTextPolicy policy = new AiListingTextPolicy();
  private static final String DATED_SOURCE =
      """
      {"product":{"modelName":"MacBook Pro 14","purchaseDate":"2024-02-12","warrantyExpiresAt":"2027-02-12",
      "specifications":{"cpu":"Apple M3 Pro","ramGb":18,"storageGb":512,"os":"macOS"}},
      "history":[{"eventId":11,"occurredOn":"2025-05-20","content":"점검"},
      {"eventId":12,"occurredOn":"2025-04-02","status":"VOID"}]}
      """;

  @ParameterizedTest
  @ValueSource(strings = {"2025년 5월 20일", "2025-5-20", "2025. 5. 20", "2025/5/20", "2025-05-20"})
  void suggestionDatesUseActualRecordedDateBeforeStrictNumberComparison(String date) {
    var result =
        policy.validateSuggestion(
            new ListingSuggestionGateway.Suggestion(
                date + " 점검 제품", date + " 정기 점검 기록과 18GB 메모리가 등록되어 있습니다."),
            DATED_SOURCE);
    assertEquals("2025-05-20 점검 제품", result.title());
    assertTrue(result.description().contains("2025-05-20"));
    assertTrue(result.description().contains("18GB"));
  }

  @Test
  void replaysTheObservedGeminiOutputWithoutRelaxingNumericGrounding() {
    var raw =
        new ListingSuggestionGateway.Suggestion(
            "Apple MacBook Pro 14 맥북 프로 M3 Pro 18GB 512GB SSD",
            "제조사: Apple, 모델명: MacBook Pro 14, 구매일: 2024-02-12, 보증 만료일: 2027-02-12. 사양: CPU Apple M3 Pro, RAM 18GB, 저장장치 512GB SSD, OS macOS. 이력: 2024년 2월 12일 제품 구매, 2025년 5월 20일 정기 점검(메모리와 저장장치 동작 점검 완료), 2025년 4월 2일 정정됨(VOID).");
    var result = policy.validateSuggestion(raw, DATED_SOURCE);
    assertEquals(
        raw.description()
            .replace("2024년 2월 12일", "2024-02-12")
            .replace("2025년 5월 20일", "2025-05-20")
            .replace("2025년 4월 2일", "2025-04-02"),
        result.description());
    assertThrows(
        CommonException.class,
        () ->
            policy.validateSuggestion(
                new ListingSuggestionGateway.Suggestion(
                    "MacBook Pro 14", "2025년 5월 20일 점검했으며 메모리는 5GB입니다."),
                DATED_SOURCE));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"2025-02-20", "2025년 2월 20일", "2025-02-30", "2025년 13월 20일", "2026-05-20"})
  void inventedOrInvalidDatesAreRejectedEvenIfTheirNumbersAppearElsewhere(String date) {
    assertThrows(
        CommonException.class,
        () ->
            policy.validateSuggestion(
                new ListingSuggestionGateway.Suggestion("MacBook Pro 14", date + " 점검 기록입니다."),
                DATED_SOURCE));
  }

  @Test
  void onlyTypedProductAndHistoryDatesAuthorizeDateNormalization() {
    String source =
        "{\"product\":{\"modelName\":\"MacBook Pro 14\"},\"history\":[{\"content\":\"2025-05-20\"}]}";
    assertThrows(
        CommonException.class,
        () ->
            policy.validateSuggestion(
                new ListingSuggestionGateway.Suggestion("MacBook Pro 14", "2025년 5월 20일 기록입니다."),
                source));
  }

  @Test
  void allowsRegisteredFactsAndPlainText() {
    var result =
        policy.validate(
            new ListingSuggestionGateway.Suggestion(
                " Apple M3 노트북 ", "메모리 18GB, 저장장치 512GB입니다.\n등록된 이력을 확인해 주세요."),
            "Apple M3 18GB 512GB");
    assertEquals("Apple M3 노트북", result.title());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<script>alert(1)</script>",
        "010-1234-5678",
        "person@example.com",
        "https://example.com",
        "구매금액 100000원",
        "시리얼 ABC",
        "실사용 1년",
        "수리 이력 없음",
        "고장 난 적 없음",
        "정품 보장",
        "구매 추천",
        "가성비",
        "새 메모리 999GB",
        "텍스트\u202e"
      })
  void rejectsSensitiveUnsupportedAndUnsafeClaims(String text) {
    assertThrows(
        CommonException.class,
        () -> policy.validate(new ListingSuggestionGateway.Suggestion("제목", text), "{}"));
  }

  @Test
  void refusesOversizeBlankAndMultilineTitle() {
    for (var value : new String[] {"", " ", "x".repeat(61), "제목\n다음"})
      assertThrows(
          CommonException.class,
          () -> policy.validate(new ListingSuggestionGateway.Suggestion(value, "설명"), "{}"));
    assertThrows(
        CommonException.class,
        () ->
            policy.validate(new ListingSuggestionGateway.Suggestion("제목", "x".repeat(2001)), "{}"));
  }

  @Test
  void preventsSensitiveSourceFromLeavingServer() {
    assertThrows(
        CommonException.class, () -> policy.validateInput("{\"model\":\"person@example.com\"}"));
  }
}
