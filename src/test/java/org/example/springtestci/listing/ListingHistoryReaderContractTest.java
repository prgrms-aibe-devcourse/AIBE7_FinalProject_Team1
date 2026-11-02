package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.example.springtestci.listing.service.ListingHistoryReader.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 전달 DTO의 상세 미제공 원문 차단과 공개 가능한 필드의 자동 검사 계약을 확인한다. */
class ListingHistoryReaderContractTest {
  @Test
  void unreviewedTextCannotCrossThePortEvenIfAProviderAccidentallySuppliesIt() {
    var text =
        new PublicText(
            "PRIVATE_TITLE", "PRIVATE_CONTENT", "PRIVATE_PROVIDER", "PRIVATE_PART", false);
    var change = new Correction(11, Instant.parse("2026-11-01T00:00:00Z"), "PRIVATE_REASON", false);
    var event =
        new Event(
            1,
            101,
            1L,
            EventType.REPAIR,
            LocalDate.of(2026, 10, 20),
            Instant.parse("2026-10-20T00:00:00Z"),
            EventStatus.VALID,
            text,
            List.of(change));
    var snapshot = new Snapshot(State.READY, false, List.of(event));
    assertFalse(snapshot.toString().contains("PRIVATE_"));
    assertFalse(JsonMapper.builder().build().writeValueAsString(snapshot).contains("PRIVATE_"));
    assertEquals(EventType.REPAIR, snapshot.events().get(0).eventType());
    assertEquals(11, snapshot.events().get(0).corrections().get(0).correctionId());
  }

  @Test
  void reviewedFragmentsAndIndependentCorrectionReviewKeepTheirOwnMeaning() {
    var text = new PublicText("공개 점검", "공개 점검 내용", null, "키보드", true);
    assertEquals("키보드", text.replacedPart());
    assertEquals("공개 점검 내용", text.content());
    var pending = new Correction(11, Instant.EPOCH, "PRIVATE_REASON", false);
    var approved = new Correction(12, Instant.EPOCH, "공개 정정 사유", true);
    assertNull(pending.reason());
    assertEquals("공개 정정 사유", approved.reason());
    assertTrue(text.reviewed());
    assertFalse(pending.reviewed());
  }
}
