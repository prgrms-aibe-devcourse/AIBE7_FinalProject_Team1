package org.example.springtestci.history.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.document.dto.ExtractedFields;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.dto.HistoryEventResponse;
import org.example.springtestci.history.entity.HistoryEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentProductEventWriterTest {

  private static final long OWNER = 1L;
  private static final long PRODUCT = 10L;
  private static final long DOCUMENT = 30L;

  @Mock private HistoryService historyService;

  private static ExtractedFields fields(
      LocalDate purchaseDate, LocalDate repairDate, String repairDescription) {
    return new ExtractedFields(
        "갤럭시북4", "NT960XGQ", purchaseDate, "OO전자", 1250000L, 12, repairDate, repairDescription);
  }

  private static HistoryEventResponse response(long eventId) {
    return new HistoryEventResponse(
        eventId,
        PRODUCT,
        HistoryEventType.PURCHASE,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        List.of(),
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void 구매일만_있으면_구매_이력_한_건을_만든다() {
    var requests =
        DocumentProductEventWriter.plan(fields(LocalDate.of(2026, 3, 1), null, null), DOCUMENT);

    assertEquals(1, requests.size());
    var purchase = requests.get(0);
    assertEquals(HistoryEventType.PURCHASE, purchase.eventType());
    assertEquals(LocalDate.of(2026, 3, 1), purchase.occurredOn());
    assertEquals("갤럭시북4 구매", purchase.title());
    assertEquals("제품명: 갤럭시북4\n모델명: NT960XGQ\n보증기간: 12개월", purchase.content());
    assertEquals("OO전자", purchase.serviceProvider());
    assertEquals(1250000L, purchase.cost());
    assertEquals(List.of(DOCUMENT), purchase.documentIds());
  }

  @Test
  void 수리일만_있으면_수리_이력_한_건을_만들고_수리_내용을_남긴다() {
    var requests =
        DocumentProductEventWriter.plan(
            fields(null, LocalDate.of(2026, 9, 5), "메인보드 교체"), DOCUMENT);

    assertEquals(1, requests.size());
    var repair = requests.get(0);
    assertEquals(HistoryEventType.REPAIR, repair.eventType());
    assertEquals(LocalDate.of(2026, 9, 5), repair.occurredOn());
    assertEquals("수리", repair.title());
    assertEquals("제품명: 갤럭시북4\n모델명: NT960XGQ\n보증기간: 12개월\n수리 내용: 메인보드 교체", repair.content());
    assertEquals("OO전자", repair.serviceProvider());
    assertEquals(1250000L, repair.cost());
  }

  @Test
  void 둘_다_있으면_두_건을_만들고_제품_정보는_구매_이력에만_담는다() {
    var requests =
        DocumentProductEventWriter.plan(
            fields(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 5), "메인보드 교체"), DOCUMENT);

    assertEquals(2, requests.size());
    var purchase = requests.get(0);
    var repair = requests.get(1);
    assertEquals(HistoryEventType.PURCHASE, purchase.eventType());
    assertEquals("제품명: 갤럭시북4\n모델명: NT960XGQ\n보증기간: 12개월", purchase.content());
    assertEquals(HistoryEventType.REPAIR, repair.eventType());
    assertEquals("수리 내용: 메인보드 교체", repair.content());
    assertNull(repair.serviceProvider());
    assertNull(repair.cost());
    assertEquals(List.of(DOCUMENT), repair.documentIds());
  }

  @Test
  void 날짜가_없으면_이력을_만들지_않고_거부한다() {
    var error =
        assertThrows(
            CommonException.class,
            () -> DocumentProductEventWriter.plan(fields(null, null, "메인보드 교체"), DOCUMENT));

    assertEquals(ErrorCode.DOCUMENT_EVENT_DATE_REQUIRED, error.errorCode());
  }

  @Test
  void 제품명이_없으면_제목은_구매이고_너무_긴_내용은_잘라낸다() {
    var longText = "가".repeat(2000);
    var requests =
        DocumentProductEventWriter.plan(
            new ExtractedFields(
                null, "M".repeat(150), LocalDate.of(2026, 3, 1), null, null, 36, null, longText),
            DOCUMENT);

    assertEquals("구매", requests.get(0).title());
    assertEquals(2000, requests.get(0).content().length());
    assertEquals(
        true, requests.get(0).content().startsWith("모델명: " + "M".repeat(150) + "\n보증기간: 36개월\n"));
  }

  @Test
  void 기록은_이력_서비스를_거치고_처음_만든_이력_ID를_돌려준다() {
    var writer = new DocumentProductEventWriter(historyService);
    when(historyService.create(anyLong(), anyLong(), any()))
        .thenReturn(response(100L), response(101L));

    Long eventId =
        writer.recordFromDocument(
            PRODUCT,
            DOCUMENT,
            OWNER,
            fields(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 5), "메인보드 교체"));

    assertEquals(100L, eventId);
    var captor = ArgumentCaptor.forClass(CreateHistoryEventRequest.class);
    verify(historyService, org.mockito.Mockito.times(2))
        .create(
            org.mockito.ArgumentMatchers.eq(OWNER),
            org.mockito.ArgumentMatchers.eq(PRODUCT),
            captor.capture());
    assertEquals(
        List.of(HistoryEventType.PURCHASE, HistoryEventType.REPAIR),
        captor.getAllValues().stream().map(CreateHistoryEventRequest::eventType).toList());
  }

  @Test
  void 날짜가_없으면_이력_서비스를_호출하지_않는다() {
    var writer = new DocumentProductEventWriter(historyService);

    assertThrows(
        CommonException.class,
        () -> writer.recordFromDocument(PRODUCT, DOCUMENT, OWNER, fields(null, null, null)));

    verify(historyService, never()).create(anyLong(), anyLong(), any());
  }
}
