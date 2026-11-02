package org.example.springtestci.history.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.history.dto.CorrectHistoryEventRequest;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.dto.HistoryEventSource;
import org.example.springtestci.history.dto.VoidHistoryEventRequest;
import org.example.springtestci.history.entity.HistoryEventStatus;
import org.example.springtestci.history.entity.HistoryEventType;
import org.example.springtestci.history.repository.HistoryRepository;
import org.example.springtestci.history.repository.HistoryRepository.HistoryChangeRecord;
import org.example.springtestci.history.repository.HistoryRepository.HistoryEventRecord;
import org.example.springtestci.history.repository.HistoryRepository.ProductSpecifications;
import org.example.springtestci.product.event.ProductFactsChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

  private static final long OWNER = 1L;
  private static final long PRODUCT = 10L;
  private static final long EVENT = 20L;
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);

  @Mock private HistoryRepository historyRepository;
  @Mock private ObjectProvider<HistoryChangeReviewRecorder> changeReviewRecorders;
  @Mock private HistoryChangeReviewRecorder changeReviewRecorder;
  @Mock private ApplicationEventPublisher events;

  private HistoryService historyService;

  @BeforeEach
  void setUp() {
    historyService =
        new HistoryService(
            historyRepository, JsonMapper.builder().build(), CLOCK, changeReviewRecorders, events);
  }

  @Test
  void createLinksOnlyValidatedDocumentsAndReturnsTheEvent() {
    CreateHistoryEventRequest request =
        new CreateHistoryEventRequest(
            HistoryEventType.REPAIR,
            LocalDate.of(2026, 10, 19),
            "키보드 수리",
            "키보드 상판을 수리함",
            "공식 서비스센터",
            120000L,
            null,
            null,
            List.of(31L));
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.documentsCanBeLinked(PRODUCT, List.of(31L))).thenReturn(true);
    when(historyRepository.insertEvent(PRODUCT, OWNER, request, null, null)).thenReturn(EVENT);
    when(historyRepository.findEvent(PRODUCT, EVENT)).thenReturn(java.util.Optional.of(event()));

    var response = historyService.create(OWNER, PRODUCT, request);

    assertEquals(EVENT, response.eventId());
    assertEquals(List.of(31L), response.documentIds());
    verify(historyRepository).linkDocuments(EVENT, List.of(31L));
    verify(historyRepository).incrementFactsVersion(PRODUCT);
    verify(events).publishEvent(new ProductFactsChanged(PRODUCT));
  }

  @Test
  void createIsBlockedWhileAnExternalDirectTransferIsPending() {
    CreateHistoryEventRequest request =
        new CreateHistoryEventRequest(
            HistoryEventType.INSPECTION,
            LocalDate.of(2026, 10, 19),
            "승계 전 점검",
            null,
            null,
            null,
            null,
            null,
            List.of());
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.hasPendingDirectTransfer(PRODUCT)).thenReturn(true);

    CommonException exception =
        assertThrows(CommonException.class, () -> historyService.create(OWNER, PRODUCT, request));

    assertEquals(ErrorCode.HISTORY_REGISTRATION_BLOCKED, exception.errorCode());
    verify(historyRepository, never()).insertEvent(anyLong(), anyLong(), any(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void createIncrementsFactsRecordsOnlyPublicSafeFieldsAndPublishesRefresh() {
    CreateHistoryEventRequest request =
        new CreateHistoryEventRequest(
            HistoryEventType.REPAIR,
            LocalDate.of(2026, 10, 19),
            "홍길동 고객 키보드 수리",
            "010-1234-5678 연락 요청",
            "개인 수리점",
            120000L,
            "키보드",
            null,
            List.of(31L));
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.documentsCanBeLinked(PRODUCT, List.of(31L))).thenReturn(true);
    when(historyRepository.insertEvent(PRODUCT, OWNER, request, null, null)).thenReturn(EVENT);
    when(historyRepository.findFactsVersion(PRODUCT)).thenReturn(2L);
    when(changeReviewRecorders.getIfAvailable()).thenReturn(changeReviewRecorder);
    when(historyRepository.findEvent(PRODUCT, EVENT)).thenReturn(java.util.Optional.of(event()));

    historyService.create(OWNER, PRODUCT, request);

    verify(historyRepository).incrementFactsVersion(PRODUCT);
    ArgumentCaptor<tools.jackson.databind.JsonNode> snapshot =
        ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
    verify(changeReviewRecorder)
        .recordCreated(
            org.mockito.ArgumentMatchers.eq(PRODUCT),
            org.mockito.ArgumentMatchers.eq(EVENT),
            org.mockito.ArgumentMatchers.eq(2L),
            snapshot.capture());
    assertEquals("CREATED", snapshot.getValue().get("changeType").asText());
    assertEquals("REPAIR", snapshot.getValue().get("eventType").asText());
    assertEquals(false, snapshot.getValue().has("title"));
    assertEquals(false, snapshot.getValue().has("content"));
    assertEquals(false, snapshot.getValue().has("cost"));
    assertEquals(false, snapshot.getValue().has("documentIds"));
    verify(events).publishEvent(new ProductFactsChanged(PRODUCT));
  }

  @Test
  void specificationHistoryUpdatesTheCurrentLaptopSpecifications() {
    ProductSpecifications before =
        new ProductSpecifications("M3", null, 8, 256, "SSD", "14 inch", 90, "macOS");
    CreateHistoryEventRequest request =
        new CreateHistoryEventRequest(
            HistoryEventType.SPEC_CHANGE,
            LocalDate.of(2026, 10, 19),
            "메모리 증설",
            null,
            null,
            null,
            null,
            Map.of("ramGb", 16),
            List.of());
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.documentsCanBeLinked(PRODUCT, List.of())).thenReturn(true);
    when(historyRepository.findSpecifications(PRODUCT)).thenReturn(before);
    when(historyRepository.insertEvent(anyLong(), anyLong(), any(), anyString(), anyString()))
        .thenReturn(EVENT);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(specEvent()));

    historyService.create(OWNER, PRODUCT, request);

    verify(historyRepository)
        .replaceSpecifications(
            PRODUCT, new ProductSpecifications("M3", null, 16, 256, "SSD", "14 inch", 90, "macOS"));
    verify(historyRepository, never()).incrementFactsVersion(anyLong());
    verify(events).publishEvent(new ProductFactsChanged(PRODUCT));
  }

  /** 사양 변경 전후 모두 숫자·제한 enum만 거래 검토에 넘기고 자유 입력은 제외한다. */
  @Test
  void specificationReviewSnapshotExcludesNewAndExistingFreeText() {
    ProductSpecifications before =
        new ProductSpecifications(
            "PRIVATE_CPU_BEFORE",
            "PRIVATE_GPU",
            8,
            256,
            "SSD",
            "PRIVATE_DISPLAY",
            90,
            "PRIVATE_OS");
    CreateHistoryEventRequest request =
        new CreateHistoryEventRequest(
            HistoryEventType.SPEC_CHANGE,
            LocalDate.of(2026, 10, 19),
            "메모리와 CPU 변경",
            null,
            null,
            null,
            null,
            Map.of("cpu", "PRIVATE_CPU_AFTER", "ramGb", 16),
            List.of());
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.documentsCanBeLinked(PRODUCT, List.of())).thenReturn(true);
    when(historyRepository.findSpecifications(PRODUCT)).thenReturn(before);
    when(historyRepository.insertEvent(anyLong(), anyLong(), any(), anyString(), anyString()))
        .thenReturn(EVENT);
    when(historyRepository.findFactsVersion(PRODUCT)).thenReturn(2L);
    when(changeReviewRecorders.getIfAvailable()).thenReturn(changeReviewRecorder);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(specEvent()));

    historyService.create(OWNER, PRODUCT, request);

    ArgumentCaptor<tools.jackson.databind.JsonNode> snapshot =
        ArgumentCaptor.forClass(tools.jackson.databind.JsonNode.class);
    verify(changeReviewRecorder)
        .recordCreated(
            org.mockito.ArgumentMatchers.eq(PRODUCT),
            org.mockito.ArgumentMatchers.eq(EVENT),
            org.mockito.ArgumentMatchers.eq(2L),
            snapshot.capture());
    var beforeSnapshot = snapshot.getValue().get("specsBefore");
    var afterSnapshot = snapshot.getValue().get("specsAfter");
    assertEquals(8, beforeSnapshot.get("ramGb").asInt());
    assertEquals(16, afterSnapshot.get("ramGb").asInt());
    assertEquals("SSD", afterSnapshot.get("storageType").asText());
    for (String field : List.of("cpu", "gpu", "display", "os")) {
      assertEquals(false, beforeSnapshot.has(field));
      assertEquals(false, afterSnapshot.has(field));
    }
    verify(historyRepository)
        .replaceSpecifications(
            PRODUCT,
            new ProductSpecifications(
                "PRIVATE_CPU_AFTER",
                "PRIVATE_GPU",
                16,
                256,
                "SSD",
                "PRIVATE_DISPLAY",
                90,
                "PRIVATE_OS"));
  }

  @Test
  void partReplacementCanBeRecordedWithoutChangingSpecifications() {
    CreateHistoryEventRequest request =
        new CreateHistoryEventRequest(
            HistoryEventType.PART_REPLACEMENT,
            LocalDate.of(2026, 10, 19),
            "동일 규격 배터리 교체",
            null,
            "공식 서비스센터",
            150000L,
            "배터리",
            null,
            List.of());
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.documentsCanBeLinked(PRODUCT, List.of())).thenReturn(true);
    when(historyRepository.insertEvent(PRODUCT, OWNER, request, null, null)).thenReturn(EVENT);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(partReplacementEvent()));

    var response = historyService.create(OWNER, PRODUCT, request);

    assertEquals(HistoryEventType.PART_REPLACEMENT, response.eventType());
    verify(historyRepository, never()).findSpecifications(anyLong());
    verify(historyRepository, never()).replaceSpecifications(anyLong(), any());
    verify(historyRepository).incrementFactsVersion(PRODUCT);
    verify(events).publishEvent(new ProductFactsChanged(PRODUCT));
  }

  @Test
  void previousOwnersEventCannotBeChanged() {
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(eventWithAuthor(99L)));

    CommonException exception =
        assertThrows(
            CommonException.class,
            () ->
                historyService.voidEvent(
                    OWNER, PRODUCT, EVENT, new VoidHistoryEventRequest("잘못 등록함")));

    assertEquals(ErrorCode.HISTORY_EVENT_CHANGE_NOT_ALLOWED, exception.errorCode());
    verify(historyRepository, never()).voidEvent(anyLong());
    verifyNoInteractions(events);
  }

  @Test
  void currentOwnerCanCorrectOwnValidGeneralEventAndTheChangeIsAudited() {
    var request = correctionRequest();
    HistoryEventRecord corrected = correctedEvent();
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(event()), java.util.Optional.of(corrected));

    var response = historyService.correct(OWNER, PRODUCT, EVENT, request);

    assertEquals("키보드 상판 교체", response.title());
    verify(historyRepository).correctEvent(EVENT, request, null, null);
    verify(historyRepository).incrementFactsVersion(PRODUCT);
    verify(events).publishEvent(new ProductFactsChanged(PRODUCT));
    verify(historyRepository)
        .insertAudit(
            org.mockito.ArgumentMatchers.eq(PRODUCT),
            org.mockito.ArgumentMatchers.eq(OWNER),
            org.mockito.ArgumentMatchers.eq(EVENT),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.eq("수리 명세서 확인"),
            org.mockito.ArgumentMatchers.eq("HISTORY_CORRECTED"));
    verify(events).publishEvent(new ProductFactsChanged(PRODUCT));
  }

  @Test
  void userWhoDoesNotOwnTheProductCannotInspectOrCorrectItsEvent() {
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(false);

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> historyService.correct(OWNER, PRODUCT, EVENT, correctionRequest()));

    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode());
    verify(historyRepository, never()).findEvent(anyLong(), anyLong());
    verify(historyRepository, never()).correctEvent(anyLong(), any(), any(), any());
    verifyNoInteractions(events);
  }

  @Test
  void transferEventCannotBeCorrectedEvenByTheCurrentOwner() {
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(transferEvent()));

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> historyService.correct(OWNER, PRODUCT, EVENT, correctionRequest()));

    assertEquals(ErrorCode.HISTORY_EVENT_CHANGE_NOT_ALLOWED, exception.errorCode());
    verify(historyRepository, never()).correctEvent(anyLong(), any(), any(), any());
  }

  @Test
  void voidEventCannotBeCorrectedAgain() {
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(voidedEvent()));

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> historyService.correct(OWNER, PRODUCT, EVENT, correctionRequest()));

    assertEquals(ErrorCode.HISTORY_EVENT_CHANGE_NOT_ALLOWED, exception.errorCode());
    verify(historyRepository, never()).correctEvent(anyLong(), any(), any(), any());
  }

  @Test
  void timelineCannotBeReadBySomeoneOtherThanTheCurrentOwner() {
    when(historyRepository.existsOwnedProduct(PRODUCT, OWNER)).thenReturn(false);

    CommonException exception =
        assertThrows(CommonException.class, () -> historyService.timeline(OWNER, PRODUCT));

    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode());
    verify(historyRepository, never()).findTimeline(anyLong());
  }

  @Test
  void newOwnerCanReadAccumulatedHistoryButOnlyTheirOwnRecordsAreEditable() {
    HistoryEventRecord previousOwnerEvent = eventWithAuthor(99L);
    HistoryEventRecord currentOwnerEvent = eventWithAuthor(OWNER);
    when(historyRepository.existsOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.findDocumentIds(PRODUCT)).thenReturn(Map.of());
    when(historyRepository.findChanges(PRODUCT)).thenReturn(Map.of());
    when(historyRepository.findTimeline(PRODUCT))
        .thenReturn(List.of(previousOwnerEvent, transferEvent(), currentOwnerEvent));

    var timeline = historyService.timeline(OWNER, PRODUCT);

    assertEquals(3, timeline.size());
    assertEquals(HistoryEventSource.PREVIOUS_OWNER, timeline.get(0).source());
    assertEquals(HistoryEventSource.SYSTEM, timeline.get(1).source());
    assertEquals(HistoryEventSource.CURRENT_OWNER, timeline.get(2).source());
    assertEquals(false, timeline.get(0).editable());
    assertEquals(false, timeline.get(1).editable());
    assertEquals(true, timeline.get(2).editable());
  }

  @Test
  void timelineReturnsEveryCorrectionBeforeTheVoidReasonInOrder() {
    when(historyRepository.existsOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.findDocumentIds(PRODUCT)).thenReturn(Map.of());
    when(historyRepository.findTimeline(PRODUCT)).thenReturn(List.of(voidedEvent()));
    when(historyRepository.findChanges(PRODUCT))
        .thenReturn(
            Map.of(
                EVENT,
                List.of(
                    new HistoryChangeRecord(
                        "HISTORY_CORRECTED",
                        "발생일 정정",
                        OffsetDateTime.parse("2026-10-20T01:00:00Z")),
                    new HistoryChangeRecord(
                        "HISTORY_CORRECTED", "제목 정정", OffsetDateTime.parse("2026-10-20T02:00:00Z")),
                    new HistoryChangeRecord(
                        "HISTORY_VOIDED", "중복 이력", OffsetDateTime.parse("2026-10-20T03:00:00Z")))));

    var response = historyService.timeline(OWNER, PRODUCT).get(0);

    assertEquals(
        List.of("발생일 정정", "제목 정정", "중복 이력"),
        response.changes().stream().map(change -> change.reason()).toList());
    assertEquals(
        List.of("HISTORY_CORRECTED", "HISTORY_CORRECTED", "HISTORY_VOIDED"),
        response.changes().stream().map(change -> change.action()).toList());
    assertEquals(
        List.of(
            OffsetDateTime.parse("2026-10-20T01:00:00Z"),
            OffsetDateTime.parse("2026-10-20T02:00:00Z"),
            OffsetDateTime.parse("2026-10-20T03:00:00Z")),
        response.changes().stream().map(change -> change.changedAt()).toList());
    assertEquals("HISTORY_VOIDED", response.changeAction());
    assertEquals("중복 이력", response.changeReason());
  }

  @Test
  void voidingSpecificationHistoryRestoresItsPreviousSpecificationsAndAudits() {
    HistoryEventRecord event = specEvent();
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(event), java.util.Optional.of(voidedSpecEvent()));
    when(historyRepository.findValidSpecificationEventsFrom(PRODUCT, EVENT))
        .thenReturn(List.of(laterStorageEvent()));

    var response =
        historyService.voidEvent(OWNER, PRODUCT, EVENT, new VoidHistoryEventRequest("실제 작업이 취소됨"));

    assertEquals(HistoryEventStatus.VOID, response.status());
    verify(historyRepository)
        .replaceSpecifications(
            PRODUCT, new ProductSpecifications("M3", null, 8, 512, "SSD", "14 inch", 90, "macOS"));
    verify(historyRepository, never()).incrementFactsVersion(anyLong());
    verify(events).publishEvent(new ProductFactsChanged(PRODUCT));
    verify(historyRepository)
        .insertAudit(
            org.mockito.ArgumentMatchers.eq(PRODUCT),
            org.mockito.ArgumentMatchers.eq(OWNER),
            org.mockito.ArgumentMatchers.eq(EVENT),
            anyString(),
            anyString(),
            org.mockito.ArgumentMatchers.eq("실제 작업이 취소됨"),
            org.mockito.ArgumentMatchers.eq("HISTORY_VOIDED"));
  }

  @Test
  void voidingGeneralHistoryAlsoIncrementsTheProductFactsVersion() {
    when(historyRepository.lockOwnedProduct(PRODUCT, OWNER)).thenReturn(true);
    when(historyRepository.findEvent(PRODUCT, EVENT))
        .thenReturn(java.util.Optional.of(event()), java.util.Optional.of(voidedEvent()));

    historyService.voidEvent(OWNER, PRODUCT, EVENT, new VoidHistoryEventRequest("중복 등록"));

    verify(historyRepository).incrementFactsVersion(PRODUCT);
    verify(events).publishEvent(new ProductFactsChanged(PRODUCT));
  }

  private static CorrectHistoryEventRequest correctionRequest() {
    return new CorrectHistoryEventRequest(
        LocalDate.of(2026, 10, 18),
        "키보드 상판 교체",
        "수리 명칭을 실제 작업 내용으로 정정",
        "공식 서비스센터",
        120000L,
        "키보드 상판",
        null,
        "수리 명세서 확인");
  }

  private static HistoryEventRecord event() {
    return eventWithAuthor(OWNER);
  }

  private static HistoryEventRecord eventWithAuthor(long authorId) {
    return new HistoryEventRecord(
        EVENT,
        PRODUCT,
        authorId,
        HistoryEventType.REPAIR,
        LocalDate.of(2026, 10, 19),
        "키보드 수리",
        "키보드 상판을 수리함",
        "공식 서비스센터",
        120000L,
        null,
        null,
        null,
        HistoryEventStatus.VALID,
        OffsetDateTime.parse("2026-10-20T00:00:00Z"),
        OffsetDateTime.parse("2026-10-20T00:00:00Z"));
  }

  private static HistoryEventRecord correctedEvent() {
    HistoryEventRecord event = event();
    return new HistoryEventRecord(
        event.eventId(),
        event.productId(),
        event.authorId(),
        event.eventType(),
        LocalDate.of(2026, 10, 18),
        "키보드 상판 교체",
        "수리 명칭을 실제 작업 내용으로 정정",
        event.serviceProvider(),
        event.cost(),
        "키보드 상판",
        null,
        null,
        HistoryEventStatus.VALID,
        event.createdAt(),
        OffsetDateTime.parse("2026-10-20T01:00:00Z"));
  }

  private static HistoryEventRecord transferEvent() {
    HistoryEventRecord event = event();
    return new HistoryEventRecord(
        event.eventId(),
        event.productId(),
        null,
        HistoryEventType.TRANSFER,
        event.occurredOn(),
        "소유권 이전",
        null,
        null,
        null,
        null,
        null,
        null,
        HistoryEventStatus.VALID,
        event.createdAt(),
        event.updatedAt());
  }

  private static HistoryEventRecord voidedEvent() {
    HistoryEventRecord event = event();
    return new HistoryEventRecord(
        event.eventId(),
        event.productId(),
        event.authorId(),
        event.eventType(),
        event.occurredOn(),
        event.title(),
        event.content(),
        event.serviceProvider(),
        event.cost(),
        event.replacedPart(),
        event.specsBefore(),
        event.specsAfter(),
        HistoryEventStatus.VOID,
        event.createdAt(),
        event.updatedAt());
  }

  private static HistoryEventRecord specEvent() {
    return new HistoryEventRecord(
        EVENT,
        PRODUCT,
        OWNER,
        HistoryEventType.SPEC_CHANGE,
        LocalDate.of(2026, 10, 19),
        "메모리 증설",
        null,
        null,
        null,
        null,
        "{\"cpu\":\"M3\",\"gpu\":null,\"ramGb\":8,\"storageGb\":256,"
            + "\"storageType\":\"SSD\",\"display\":\"14 inch\","
            + "\"batteryHealthPercent\":90,\"os\":\"macOS\"}",
        "{\"cpu\":\"M3\",\"gpu\":null,\"ramGb\":16,\"storageGb\":256,"
            + "\"storageType\":\"SSD\",\"display\":\"14 inch\","
            + "\"batteryHealthPercent\":90,\"os\":\"macOS\"}",
        HistoryEventStatus.VALID,
        OffsetDateTime.parse("2026-10-20T00:00:00Z"),
        OffsetDateTime.parse("2026-10-20T00:00:00Z"));
  }

  private static HistoryEventRecord partReplacementEvent() {
    return new HistoryEventRecord(
        EVENT,
        PRODUCT,
        OWNER,
        HistoryEventType.PART_REPLACEMENT,
        LocalDate.of(2026, 10, 19),
        "동일 규격 배터리 교체",
        null,
        "공식 서비스센터",
        150000L,
        "배터리",
        null,
        null,
        HistoryEventStatus.VALID,
        OffsetDateTime.parse("2026-10-20T00:00:00Z"),
        OffsetDateTime.parse("2026-10-20T00:00:00Z"));
  }

  private static HistoryEventRecord voidedSpecEvent() {
    HistoryEventRecord event = specEvent();
    return new HistoryEventRecord(
        event.eventId(),
        event.productId(),
        event.authorId(),
        event.eventType(),
        event.occurredOn(),
        event.title(),
        event.content(),
        event.serviceProvider(),
        event.cost(),
        event.replacedPart(),
        event.specsBefore(),
        event.specsAfter(),
        HistoryEventStatus.VOID,
        event.createdAt(),
        event.updatedAt());
  }

  private static HistoryEventRecord laterStorageEvent() {
    return new HistoryEventRecord(
        EVENT + 1,
        PRODUCT,
        OWNER,
        HistoryEventType.SPEC_CHANGE,
        LocalDate.of(2026, 10, 20),
        "저장장치 교체",
        null,
        null,
        null,
        null,
        "{\"cpu\":\"M3\",\"gpu\":null,\"ramGb\":16,\"storageGb\":256,"
            + "\"storageType\":\"SSD\",\"display\":\"14 inch\","
            + "\"batteryHealthPercent\":90,\"os\":\"macOS\"}",
        "{\"cpu\":\"M3\",\"gpu\":null,\"ramGb\":16,\"storageGb\":512,"
            + "\"storageType\":\"SSD\",\"display\":\"14 inch\","
            + "\"batteryHealthPercent\":90,\"os\":\"macOS\"}",
        HistoryEventStatus.VALID,
        OffsetDateTime.parse("2026-10-20T01:00:00Z"),
        OffsetDateTime.parse("2026-10-20T01:00:00Z"));
  }
}
