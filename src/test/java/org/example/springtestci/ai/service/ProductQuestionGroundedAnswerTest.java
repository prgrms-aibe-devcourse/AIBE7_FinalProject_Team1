package org.example.springtestci.ai.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.example.springtestci.ai.dto.ProductAnswerResponse.Evidence;
import org.junit.jupiter.api.Test;

class ProductQuestionGroundedAnswerTest {
  @Test
  void leavesOrdinaryAnswersToTheExistingModelValidation() {
    assertNull(
        ProductQuestionGroundedAnswer.compose(
            List.of(event(1, "VALID")), List.of(event(1, "VALID")), false));
  }

  @Test
  void keepsQueriedSpecsAndValidEventsWithoutUsingEventProse() {
    var specs =
        new Evidence(
            "specs",
            "SPECS",
            "사양",
            Map.of("modelName", "시험 모델", "specifications", Map.of("ramGb", 18, "cpu", "시험 CPU")));
    var valid = event(2, "VALID");
    var voided = event(1, "VOID");
    var result =
        ProductQuestionGroundedAnswer.compose(List.of(valid), List.of(specs, valid, voided), true);
    assertEquals(
        List.of("event:1", "specs", "event:2"),
        result.evidence().stream().map(Evidence::key).toList());
    assertTrue(result.text().contains("18GB"));
    assertTrue(result.text().contains("시험 CPU"));
    assertTrue(result.text().contains("무효") && result.text().contains("미검토"));
    assertTrue(result.text().contains("보증 만료일이 등록되지 않아"));
    assertFalse(result.text().contains("SECRET_PROSE"));
  }

  @Test
  void showsOnlyRegisteredWarrantyDateWhenWarrantyWasActuallyQueried() {
    var warranty =
        new Evidence(
            "warranty",
            "WARRANTY",
            "보증",
            Map.of("warrantyExpiresOn", "2027-02-12", "asOf", "2026-11-01"));
    var result =
        ProductQuestionGroundedAnswer.compose(
            List.of(warranty), List.of(warranty, event(1, "VOID")), false);
    assertTrue(result.text().contains("2027-02-12"));
    assertFalse(result.text().contains("보증은 유효"));
    assertFalse(result.text().contains("보증 만료일이 등록되지 않아"));
  }

  @Test
  void keepsVoidNoticeWithinEvidenceAndTextBoundsForLargeHistories() {
    List<Evidence> delivered = new ArrayList<>();
    for (int i = 1; i <= 30; i++) delivered.add(event(i, i == 30 ? "VOID" : "VALID"));
    var result = ProductQuestionGroundedAnswer.compose(delivered.subList(0, 20), delivered, false);
    assertEquals(20, result.evidence().size());
    assertEquals("event:30", result.evidence().get(0).key());
    assertTrue(result.text().contains("무효"));
    assertTrue(result.text().contains("일부 근거"));
    assertTrue(result.text().length() <= 2000);
  }

  @Test
  void overlongFactsKeepSafeNoticesAndEvidenceWithoutCuttingAClaimMidSentence() {
    var specs =
        new Evidence(
            "specs", "SPECS", "사양", Map.of("specifications", Map.of("cpu", "A".repeat(2000))));
    var result =
        ProductQuestionGroundedAnswer.compose(List.of(), List.of(event(1, "VOID"), specs), true);
    assertTrue(result.text().length() <= 2000);
    assertTrue(result.text().contains("무효"));
    assertTrue(result.text().contains("보증 만료일이 등록되지 않아"));
    assertFalse(result.text().contains("AAA"));
    assertEquals(2, result.evidence().size());
  }

  private static Evidence event(int id, String status) {
    return new Evidence(
        "event:" + id,
        "EVENT",
        "기록",
        Map.of(
            "eventId",
            id,
            "type",
            "REPAIR",
            "status",
            status,
            "occurredOn",
            "2026-09-18",
            "detailsReviewed",
            false,
            "content",
            "SECRET_PROSE 실제로 수리를 받았습니다."));
  }
}
