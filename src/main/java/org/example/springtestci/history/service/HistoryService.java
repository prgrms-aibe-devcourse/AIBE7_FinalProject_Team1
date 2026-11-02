package org.example.springtestci.history.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.history.dto.CorrectHistoryEventRequest;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.dto.HistoryEventResponse;
import org.example.springtestci.history.dto.HistoryEventSource;
import org.example.springtestci.history.dto.VoidHistoryEventRequest;
import org.example.springtestci.history.entity.HistoryEventStatus;
import org.example.springtestci.history.entity.HistoryEventType;
import org.example.springtestci.history.repository.HistoryRepository;
import org.example.springtestci.history.repository.HistoryRepository.HistoryChangeRecord;
import org.example.springtestci.history.repository.HistoryRepository.HistoryEventRecord;
import org.example.springtestci.history.repository.HistoryRepository.ProductSpecifications;
import org.example.springtestci.product.event.ProductFactsChanged;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 제품 이력의 등록·조회·정정·무효화 업무를 처리한다.
 *
 * <p>Controller와 {@link HistoryRepository} 사이에서 소유권, 변경 가능 상태, 사양 값 검증 및 트랜잭션을 관리한다.
 */
@Service
public class HistoryService {

  // 사용자가 임의의 필드명을 저장하지 못하도록 변경 가능한 제품 사양 키를 제한한다.
  private static final Set<String> SPEC_KEYS =
      Set.of(
          "cpu",
          "gpu",
          "ramGb",
          "storageGb",
          "storageType",
          "display",
          "batteryHealthPercent",
          "os");

  private final HistoryRepository historyRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final ObjectProvider<HistoryChangeReviewRecorder> changeReviewRecorders;
  private final ApplicationEventPublisher events;

  public HistoryService(
      HistoryRepository historyRepository,
      ObjectMapper objectMapper,
      Clock clock,
      ObjectProvider<HistoryChangeReviewRecorder> changeReviewRecorders,
      ApplicationEventPublisher events) {
    this.historyRepository = historyRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.changeReviewRecorders = changeReviewRecorders;
    this.events = events;
  }

  /** 현재 소유자의 이력·증빙·제품 버전을 함께 저장하고, 요약 갱신은 커밋 후 수신자가 접수한다. */
  @Transactional
  public HistoryEventResponse create(
      long ownerId, long productId, CreateHistoryEventRequest request) {
    requireOwnedLock(productId, ownerId);
    if (historyRepository.hasPendingDirectTransfer(productId)) {
      throw new CommonException(ErrorCode.HISTORY_REGISTRATION_BLOCKED);
    }
    validateCommon(request.eventType(), request.occurredOn(), request.specsAfter());
    if (request.eventType() == HistoryEventType.TRANSFER) {
      throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
    }

    // List는 응답 순서를 유지하고, 내부의 Set 검사는 중복 문서 ID를 찾는 데 사용한다.
    List<Long> documentIds = normalizedDocumentIds(request.documentIds());
    if (!historyRepository.documentsCanBeLinked(productId, documentIds)) {
      throw new CommonException(ErrorCode.INVALID_HISTORY_DOCUMENT);
    }

    String specsBeforeJson = null;
    String specsAfterJson = null;
    ProductSpecifications after = null;
    if (hasSpecificationChanges(request.specsAfter())) {
      ProductSpecifications before = historyRepository.findSpecifications(productId);
      after = mergeSpecifications(before, request.specsAfter());
      specsBeforeJson = json(toMap(before));
      specsAfterJson = json(toMap(after));
    }

    long eventId =
        historyRepository.insertEvent(productId, ownerId, request, specsBeforeJson, specsAfterJson);
    historyRepository.linkDocuments(eventId, documentIds);
    // 사양 교체 경로는 저장소가 버전을 올리므로 일반 이력에서만 별도로 증가시킨다.
    if (after != null) historyRepository.replaceSpecifications(productId, after);
    else historyRepository.incrementFactsVersion(productId);
    long factsVersion = historyRepository.findFactsVersion(productId);
    HistoryChangeReviewRecorder recorder = changeReviewRecorders.getIfAvailable();
    if (recorder != null) {
      recorder.recordCreated(
          productId,
          eventId,
          factsVersion,
          createdSnapshot(request, specsBeforeJson, specsAfterJson));
    }
    var response = getOwnedEvent(ownerId, productId, eventId, documentIds);
    events.publishEvent(new ProductFactsChanged(productId));
    return response;
  }

  /** 소유권을 확인한 뒤 이력, 연결 문서, 최근 변경 정보를 합쳐 타임라인을 만든다. */
  @Transactional(readOnly = true)
  public List<HistoryEventResponse> timeline(long ownerId, long productId) {
    if (!historyRepository.existsOwnedProduct(productId, ownerId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    // eventId를 Map의 키로 사용하면 반복 조회 없이 각 이벤트에 문서와 변경 정보를 연결할 수 있다.
    Map<Long, List<Long>> documents = historyRepository.findDocumentIds(productId);
    Map<Long, HistoryChangeRecord> changes = historyRepository.findLatestChanges(productId);
    return historyRepository.findTimeline(productId).stream()
        .map(
            event ->
                toResponse(
                    ownerId,
                    event,
                    documents.getOrDefault(event.eventId(), List.of()),
                    changes.get(event.eventId())))
        .toList();
  }

  /** 본인이 작성한 유효 이력을 정정한다. 감사 기록과 버전이 커밋된 뒤 AI가 최신 자료를 읽는다. */
  @Transactional
  public HistoryEventResponse correct(
      long ownerId, long productId, long eventId, CorrectHistoryEventRequest request) {
    requireOwnedLock(productId, ownerId);
    HistoryEventRecord before = requireChangeableEvent(productId, eventId, ownerId);
    validateCommon(before.eventType(), request.occurredOn(), request.specsAfter());

    String specsBeforeJson = before.specsBefore();
    String specsAfterJson = before.specsAfter();
    ProductSpecifications rebuildBase = null;
    boolean hadSpecificationChanges = before.specsAfter() != null;
    boolean hasSpecificationChanges = hasSpecificationChanges(request.specsAfter());
    if (hadSpecificationChanges != hasSpecificationChanges) {
      throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
    }
    if (hadSpecificationChanges) {
      var originalSpecs = parseMap(before.specsBefore());
      // H2가 JSON을 문자열로 반환해도 원래 객체를 다시 저장해 반복 정정 시 이중 인코딩을 막는다.
      specsBeforeJson = json(originalSpecs);
      rebuildBase = specifications(originalSpecs);
      specsAfterJson = json(toMap(mergeSpecifications(rebuildBase, request.specsAfter())));
    }

    historyRepository.correctEvent(eventId, request, specsBeforeJson, specsAfterJson);
    if (rebuildBase != null) rebuildSpecifications(productId, eventId, rebuildBase);
    else historyRepository.incrementFactsVersion(productId);
    HistoryEventRecord after = historyRepository.findEvent(productId, eventId).orElseThrow();
    historyRepository.insertAudit(
        productId,
        ownerId,
        eventId,
        json(before),
        json(after),
        request.reason(),
        "HISTORY_CORRECTED");
    var response =
        toResponse(
            ownerId,
            after,
            historyRepository.findDocumentIds(productId).getOrDefault(eventId, List.of()),
            historyRepository.findLatestChanges(productId).get(eventId));
    events.publishEvent(new ProductFactsChanged(productId));
    return response;
  }

  /** 이력을 보존한 채 무효 처리하고 제품 버전을 올린다. 롤백된 변경은 요약 갱신으로 전달되지 않는다. */
  @Transactional
  public HistoryEventResponse voidEvent(
      long ownerId, long productId, long eventId, VoidHistoryEventRequest request) {
    requireOwnedLock(productId, ownerId);
    HistoryEventRecord before = requireChangeableEvent(productId, eventId, ownerId);
    ProductSpecifications rebuildBase =
        before.specsAfter() != null ? specifications(parseMap(before.specsBefore())) : null;

    historyRepository.voidEvent(eventId);
    if (rebuildBase != null) rebuildSpecifications(productId, eventId, rebuildBase);
    else historyRepository.incrementFactsVersion(productId);
    HistoryEventRecord after = historyRepository.findEvent(productId, eventId).orElseThrow();
    historyRepository.insertAudit(
        productId, ownerId, eventId, json(before), json(after), request.reason(), "HISTORY_VOIDED");
    var response =
        toResponse(
            ownerId,
            after,
            historyRepository.findDocumentIds(productId).getOrDefault(eventId, List.of()),
            historyRepository.findLatestChanges(productId).get(eventId));
    events.publishEvent(new ProductFactsChanged(productId));
    return response;
  }

  private void rebuildSpecifications(
      long productId, long changedEventId, ProductSpecifications base) {
    // LinkedHashMap은 정해진 사양 키 순서를 유지해 JSON과 디버깅 결과를 읽기 쉽게 만든다.
    Map<String, Object> current = new LinkedHashMap<>(toMap(base));
    for (HistoryEventRecord event :
        historyRepository.findValidSpecificationEventsFrom(productId, changedEventId)) {
      Map<String, Object> before = parseMap(event.specsBefore());
      Map<String, Object> after = parseMap(event.specsAfter());
      for (String key : SPEC_KEYS) {
        if (!java.util.Objects.equals(before.get(key), after.get(key))) {
          current.put(key, after.get(key));
        }
      }
    }
    historyRepository.replaceSpecifications(productId, specifications(current));
  }

  private HistoryEventRecord requireChangeableEvent(long productId, long eventId, long ownerId) {
    HistoryEventRecord event =
        historyRepository
            .findEvent(productId, eventId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    if (event.status() == HistoryEventStatus.VOID
        || event.eventType() == HistoryEventType.TRANSFER
        || event.authorId() == null
        || event.authorId() != ownerId) {
      throw new CommonException(ErrorCode.HISTORY_EVENT_CHANGE_NOT_ALLOWED);
    }
    return event;
  }

  private void validateCommon(
      HistoryEventType eventType, java.time.LocalDate occurredOn, Map<String, Object> specsAfter) {
    if (occurredOn.isAfter(java.time.LocalDate.now(clock))) {
      throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
    }
    boolean hasSpecs = hasSpecificationChanges(specsAfter);
    if ((eventType == HistoryEventType.SPEC_CHANGE && !hasSpecs)
        || (!eventType.changesSpecifications() && hasSpecs)) {
      throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
    }
    if (hasSpecs
        && (!SPEC_KEYS.containsAll(specsAfter.keySet())
            || specsAfter.values().stream().anyMatch(java.util.Objects::isNull))) {
      throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
    }
  }

  private static boolean hasSpecificationChanges(Map<String, Object> specsAfter) {
    return specsAfter != null && !specsAfter.isEmpty();
  }

  private ProductSpecifications mergeSpecifications(
      ProductSpecifications before, Map<String, Object> changes) {
    Map<String, Object> merged = new LinkedHashMap<>(toMap(before));
    merged.putAll(changes);
    ProductSpecifications result = specifications(merged);
    if (result.equals(before)) throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
    return result;
  }

  private ProductSpecifications specifications(Map<String, Object> values) {
    Integer ramGb = integer(values.get("ramGb"));
    Integer storageGb = integer(values.get("storageGb"));
    Integer battery = integer(values.get("batteryHealthPercent"));
    String storageType = string(values.get("storageType"));
    if ((ramGb != null && ramGb < 1)
        || (storageGb != null && storageGb < 1)
        || (battery != null && (battery < 0 || battery > 100))
        || (storageType != null && !Set.of("SSD", "HDD", "MIXED").contains(storageType))) {
      throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
    }
    return new ProductSpecifications(
        string(values.get("cpu")),
        string(values.get("gpu")),
        ramGb,
        storageGb,
        storageType,
        string(values.get("display")),
        battery,
        string(values.get("os")));
  }

  private static Integer integer(Object value) {
    if (value == null) return null;
    if (value instanceof Number number && number.doubleValue() == number.intValue()) {
      return number.intValue();
    }
    throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
  }

  private static String string(Object value) {
    if (value == null) return null;
    if (!(value instanceof String text)) throw new CommonException(ErrorCode.INVALID_HISTORY_EVENT);
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static Map<String, Object> toMap(ProductSpecifications specs) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("cpu", specs.cpu());
    values.put("gpu", specs.gpu());
    values.put("ramGb", specs.ramGb());
    values.put("storageGb", specs.storageGb());
    values.put("storageType", specs.storageType());
    values.put("display", specs.display());
    values.put("batteryHealthPercent", specs.batteryHealthPercent());
    values.put("os", specs.os());
    return values;
  }

  private List<Long> normalizedDocumentIds(List<Long> documentIds) {
    if (documentIds == null) return List.of();
    if (new HashSet<>(documentIds).size() != documentIds.size()) {
      throw new CommonException(ErrorCode.INVALID_HISTORY_DOCUMENT);
    }
    return List.copyOf(documentIds);
  }

  private HistoryEventResponse getOwnedEvent(
      long ownerId, long productId, long eventId, List<Long> documentIds) {
    return toResponse(
        ownerId, historyRepository.findEvent(productId, eventId).orElseThrow(), documentIds, null);
  }

  private HistoryEventResponse toResponse(
      long ownerId, HistoryEventRecord event, List<Long> documentIds, HistoryChangeRecord change) {
    HistoryEventSource source =
        event.eventType() == HistoryEventType.TRANSFER
            ? HistoryEventSource.SYSTEM
            : java.util.Objects.equals(event.authorId(), ownerId)
                ? HistoryEventSource.CURRENT_OWNER
                : HistoryEventSource.PREVIOUS_OWNER;
    boolean editable =
        event.status() == HistoryEventStatus.VALID
            && event.eventType() != HistoryEventType.TRANSFER
            && java.util.Objects.equals(event.authorId(), ownerId);
    return new HistoryEventResponse(
        event.eventId(),
        event.productId(),
        event.eventType(),
        event.occurredOn(),
        event.title(),
        event.content(),
        event.serviceProvider(),
        event.cost(),
        event.replacedPart(),
        parseMap(event.specsBefore()),
        parseMap(event.specsAfter()),
        event.status(),
        source,
        editable,
        documentIds,
        change == null ? null : change.action(),
        change == null ? null : change.reason(),
        change == null ? null : change.changedAt(),
        event.createdAt(),
        event.updatedAt());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseMap(String value) {
    if (value == null) return null;
    try {
      Object parsed = objectMapper.readValue(value, Object.class);
      if (parsed instanceof String encoded) {
        return objectMapper.readValue(encoded, Map.class);
      }
      return (Map<String, Object>) parsed;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Failed to deserialize history specifications", exception);
    }
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Failed to serialize history facts", exception);
    }
  }

  /** 거래 당사자에게 전달 가능한 구조화 필드만 복사하고 자유 입력·비용·문서·계정 정보는 제외한다. */
  private JsonNode createdSnapshot(
      CreateHistoryEventRequest request, String specsBeforeJson, String specsAfterJson) {
    var snapshot = objectMapper.createObjectNode();
    snapshot.put("changeType", "CREATED");
    snapshot.put("eventType", request.eventType().name());
    snapshot.put("occurredOn", request.occurredOn().toString());
    snapshot.put("status", HistoryEventStatus.VALID.name());
    if (specsBeforeJson != null)
      snapshot.set("specsBefore", publicSpecificationSnapshot(specsBeforeJson));
    if (specsAfterJson != null)
      snapshot.set("specsAfter", publicSpecificationSnapshot(specsAfterJson));
    return snapshot;
  }

  /** 공개 검토 근거가 없는 사양 자유 문자열은 제외하고 검증된 숫자·제한 enum만 거래 사본으로 만든다. */
  private JsonNode publicSpecificationSnapshot(String specificationsJson) {
    Map<String, Object> specifications = parseMap(specificationsJson);
    var snapshot = objectMapper.createObjectNode();
    copyPublicSpecification(specifications, snapshot, "ramGb");
    copyPublicSpecification(specifications, snapshot, "storageGb");
    copyPublicSpecification(specifications, snapshot, "storageType");
    copyPublicSpecification(specifications, snapshot, "batteryHealthPercent");
    return snapshot;
  }

  /** 검증된 공개 사양 키만 거래 검토 사본에 복사한다. 자유 입력 사양은 호출 대상에 넣지 않는다. */
  private void copyPublicSpecification(
      Map<String, Object> specifications,
      tools.jackson.databind.node.ObjectNode snapshot,
      String field) {
    Object value = specifications.get(field);
    if (value != null) snapshot.set(field, objectMapper.valueToTree(value));
  }

  private void requireOwnedLock(long productId, long ownerId) {
    if (!historyRepository.lockOwnedProduct(productId, ownerId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
  }
}
