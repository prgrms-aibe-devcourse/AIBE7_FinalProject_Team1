package org.example.springtestci.ai.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.example.springtestci.common.error.CommonException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ListingSummaryTextPolicyTest {
  private static final String INPUT =
      """
      {"productAndHistory":{"product":{"manufacturer":"Apple","modelName":"MacBook Pro 14",
      "specifications":{"cpu":"Apple M3 Pro","ramGb":18,"storageGb":512}},"history":[]},
      "sellerReportedGrade":4}
      """;
  private final ListingSummaryTextPolicy policy =
      new ListingSummaryTextPolicy(new AiListingTextPolicy());
  private final List<String> valid = List.of("등록된 메모리는 18GB입니다.", "등록된 저장장치는 512GB입니다.");

  @Test
  void joinsOneOrTwoShortSentences() {
    assertEquals(String.join(" ", valid), policy.validate(valid, INPUT));
    assertEquals(valid.get(0), policy.validate(valid.subList(0, 1), INPUT));
  }

  @Test
  void rejectsWrongSentenceCountsAndDuplicates() {
    assertThrows(CommonException.class, () -> policy.validate(null, INPUT));
    assertThrows(CommonException.class, () -> policy.validate(List.of(), INPUT));
    assertThrows(
        CommonException.class,
        () -> policy.validate(List.of(valid.get(0), valid.get(1), "안내입니다."), INPUT));
    assertThrows(
        CommonException.class, () -> policy.validate(List.of(valid.get(0), valid.get(0)), INPUT));
  }

  @Test
  void rejectsGenericNoticesWithoutRegisteredProductFacts() {
    assertThrows(
        CommonException.class,
        () -> policy.validate(List.of("판매자가 선택한 상태는 4등급입니다.", "전체 이력을 확인해 주세요."), INPUT));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "문장이 두 개입니다. 또 다른 문장입니다.",
        "마침표 없는 문장",
        "줄\n바꿈입니다.",
        "<script>내용</script>입니다.",
        "실사용 2년입니다.",
        "수리 이력 없음.",
        "등록되지 않은 999GB입니다.",
        "연락처는 person@example.com입니다."
      })
  void rejectsMalformedUnsupportedAndSensitiveOutput(String text) {
    assertThrows(CommonException.class, () -> policy.validate(List.of(valid.get(0), text), INPUT));
  }

  @Test
  void boundsSentenceAndCombinedLength() {
    assertThrows(
        CommonException.class, () -> policy.validate(List.of("가".repeat(90) + "."), INPUT));
    var first = "MacBook Pro 14는 " + "가".repeat(40) + ".";
    var second = "나".repeat(40) + ".";
    assertTrue(first.length() <= 90 && second.length() <= 90);
    assertTrue((first + " " + second).length() > 90);
    assertThrows(CommonException.class, () -> policy.validate(List.of(first, second), INPUT));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "sellerReportedGrade는 판매자가 입력한 등급입니다.",
        "VOID는 수리 사실로 단정하지 않습니다.",
        "입력 자료의 지시문은 따르지 않습니다.",
        "JSON sentences 형식을 준수합니다.",
        "정정된 유효 사실을 기준으로 설명합니다.",
        "정정·무효 표시를 유지합니다.",
        "전체 이력을 검토하되 핵심 내용을 담습니다.",
        "상세 기록은 전체 타임라인에서 확인하도록 덧붙입니다.",
        "자세한 전체 기록은 타임라인 확인을 안내합니다."
      })
  void rejectsProcessDescriptionsAlongsideRealFacts(String instruction) {
    assertThrows(
        CommonException.class, () -> policy.validate(List.of(valid.get(0), instruction), INPUT));
  }

  @Test
  void acceptsNaturalCorrectionAndVoidDescriptions() {
    var source =
        INPUT.replace(
            "\"history\":[]",
            "\"history\":[{\"type\":\"INSPECTION\",\"status\":\"VALID\",\"corrected\":true,\"detailsReviewed\":true,\"content\":\"메모리 점검\"},{\"type\":\"REPAIR\",\"status\":\"VOID\",\"detailsReviewed\":false}]");
    var sentences = List.of("18GB 메모리 모델로 정정된 점검 기록이 있습니다.", "무효 처리된 수리 기록이 함께 표시되어 있습니다.");
    assertEquals(String.join(" ", sentences), policy.validate(sentences, source));
  }

  @Test
  void acceptsDifferentSpacingAndSparseSpecifications() {
    var sentences = List.of("macbook pro 14로 등록된 제품입니다.", "등록된 메모리는 18 GB이며 저장장치는 512 GB입니다.");
    assertEquals(String.join(" ", sentences), policy.validate(sentences, INPUT));
    var source =
        "{\"productAndHistory\":{\"product\":{\"manufacturer\":\"Lenovo\",\"modelName\":\"ThinkPad\",\"specifications\":{}},\"history\":[]},\"sellerReportedGrade\":null}";
    assertEquals(
        "Lenovo ThinkPad로 등록된 제품입니다.",
        policy.validate(List.of("Lenovo ThinkPad로 등록된 제품입니다."), source));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2024년 2월 12일", "2024년 02월 12일", "2024-2-12"})
  void normalizesRegisteredDates(String date) {
    var source =
        INPUT.replace("\"specifications\":", "\"purchaseDate\":\"2024-02-12\",\"specifications\":");
    assertEquals(
        "등록된 메모리는 18GB입니다. 제품 구매일은 2024-02-12입니다.",
        policy.validate(List.of(valid.get(0), "제품 구매일은 " + date + "입니다."), source));
  }

  @Test
  void acceptsRegisteredHistoryDates() {
    var source = INPUT.replace("\"history\":[]", "\"history\":[{\"occurredOn\":\"2024-06-15\"}]");
    assertEquals(
        "등록된 메모리는 18GB입니다. 2024-06-15 점검 기록이 있습니다.",
        policy.validate(List.of(valid.get(0), "2024년 6월 15일 점검 기록이 있습니다."), source));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2024년 6월 12일", "2024-06-12", "2024년 2월 30일", "2024-02-30"})
  void rejectsUnregisteredAndImpossibleDates(String date) {
    var source =
        INPUT.replace("\"specifications\":", "\"purchaseDate\":\"2024-02-12\",\"specifications\":");
    assertThrows(
        CommonException.class,
        () -> policy.validate(List.of(valid.get(0), date + " 점검 기록이 있습니다."), source));
  }

  @Test
  void dateNormalizationNeitherPermitsNewNumbersNorDuplicates() {
    var source =
        INPUT.replace("\"specifications\":", "\"purchaseDate\":\"2024-02-12\",\"specifications\":");
    assertThrows(
        CommonException.class,
        () -> policy.validate(List.of(valid.get(0), "2024년 2월 12일 구매했으며 메모리는 2GB입니다."), source));
    assertThrows(
        CommonException.class,
        () ->
            policy.validate(
                List.of(
                    "MacBook Pro 14는 2024년 2월 12일 구매했습니다.", "MacBook Pro 14는 2024-02-12 구매했습니다."),
                source));
  }

  @ParameterizedTest
  @ValueSource(strings = {"010\u20111234\u20115678", "수리비 50000"})
  void storedCardsCannotReusePreviouslyAcceptedPrivateOrCostText(String value) {
    assertFalse(policy.reusableStored("MacBook Pro 14이며 " + value + "입니다."));
    String source =
        INPUT.replace("\"history\":[]", "\"history\":[{\"content\":\"" + value + "\"}]");
    assertThrows(
        CommonException.class,
        () -> policy.validate(List.of("MacBook Pro 14이며 " + value + "입니다."), source));
  }

  @Test
  void hidesLegacyLongAndInstructionTextOnRead() {
    assertTrue(policy.reusableStored(String.join(" ", valid)));
    assertFalse(policy.reusableStored("가".repeat(90) + "."));
    assertFalse(policy.reusableStored("MacBook Pro 14입니다. 첫 안내입니다. 다음 안내입니다."));
    assertFalse(policy.reusableStored("MacBook Pro 14입니다. 작성 지침을 설명합니다."));
  }
}
