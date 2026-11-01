package org.example.springtestci.ai.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.example.springtestci.ai.dto.ProductAnswerResponse;
import org.example.springtestci.ai.dto.ProductAnswerResponse.Evidence;
import org.example.springtestci.ai.service.ProductQuestionSource.Snapshot;
import org.example.springtestci.ai.service.ProductQuestionSource.Target;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** 질문→허용 자료 조회→답변·근거 검증→최신 권한 재검사 순서다. 이력·초안·판매글은 변경하지 않는다. */
@Service
public class ProductQuestionService {
  public static final String NO_RECORD =
      "등록된 기록으로는 질문하신 내용을 확인할 수 없어요. 기록이 없다는 사실이 수리·고장이 없었다는 뜻은 아니에요.";
  private static final String OUTSIDE =
      "이 기능은 등록된 제품 사양·보증·이력만 설명해요. 개인정보·비공개 문서 제공이나 구매·가격·품질·안전성·정품 판단은 할 수 없어요.";
  private static final Pattern OUT_OF_SCOPE =
      Pattern.compile(
          "(?i)(시리얼|serial|증빙|영수증|보증서|OCR|계좌|전화번호|연락처|주소|구매\\s*(추천|비추천)|가성비|적정\\s*(가격|시세)|정품|안전한가|안전성|품질\\s*(평가|보장)|얼마에\\s*(사|팔)|수리비|구매금액)");
  private final ProductQuestionSource sources;
  private final ProductQuestionGateway gateway;
  private final AiSuggestionRateLimiter limits;
  private final AiListingTextPolicy textPolicy;
  private final AiSecurityValidator security;
  private final Clock clock;

  public ProductQuestionService(
      ProductQuestionSource sources,
      ProductQuestionGateway gateway,
      AiSuggestionRateLimiter limits,
      AiListingTextPolicy textPolicy,
      AiSecurityValidator security,
      Clock clock) {
    this.sources = sources;
    this.gateway = gateway;
    this.limits = limits;
    this.textPolicy = textPolicy;
    this.security = security;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ProductAnswerResponse ask(Target target, long userId, String question) {
    if (question == null || question.isBlank() || question.length() > 1000)
      throw new CommonException(ErrorCode.INVALID_INPUT);
    var before = sources.capture(target, userId);
    security.validateUserInput(question);
    if (OUT_OF_SCOPE.matcher(question).find()) {
      unchanged(before, sources.capture(target, userId));
      return response(OUTSIDE, List.of(), before);
    }
    try {
      textPolicy.validateInput(question);
    } catch (CommonException invalidQuestion) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    if (!gateway.configured()) throw new CommonException(ErrorCode.AI_UNAVAILABLE);
    var lease = limits.acquire(userId);
    try {
      var session = new ReadSession(target, userId, before);
      var answer = gateway.ask(question.strip(), session);
      if (session.called.isEmpty()) invalid();
      if (answer == null || answer.sourceKeys() == null || answer.sourceKeys().size() > 20)
        invalid();
      var unique = new LinkedHashSet<>(answer.sourceKeys());
      if (unique.size() != answer.sourceKeys().size()) invalid();
      List<Evidence> evidence = new ArrayList<>();
      for (String key : unique) {
        var item = session.delivered.get(key);
        if (item == null) invalid();
        evidence.add(item);
      }
      String text =
          evidence.isEmpty()
              ? NO_RECORD
              : textPolicy
                  .validate(
                      new ListingSuggestionGateway.Suggestion("제품 기록 답변", answer.answer()),
                      session.deliveredJson.toString())
                  .description();
      // 무효 기록은 무효 표시로 충분하다. 유효하지만 미검토인 기록만 별도의 확인 불가 안내를 요구한다.
      for (var item : evidence) {
        boolean voided = "VOID".equals(item.facts().get("status"));
        if (voided && !text.contains("무효")) invalid();
        if (!voided
            && Boolean.FALSE.equals(item.facts().get("detailsReviewed"))
            && !text.contains("미검토")
            && !text.contains("확인할 수 없")) invalid();
      }
      unchanged(before, sources.capture(target, userId));
      return response(text, evidence, before);
    } finally {
      limits.release(lease);
    }
  }

  private ProductAnswerResponse response(String text, List<Evidence> evidence, Snapshot source) {
    return new ProductAnswerResponse(
        text,
        evidence,
        source.factsVersion(),
        source.contentVersion(),
        Instant.now(clock),
        source.sample());
  }

  private static void unchanged(Snapshot before, Snapshot after) {
    if (!before.fingerprint().equals(after.fingerprint()))
      throw new CommonException(ErrorCode.AI_SOURCE_CHANGED);
  }

  private static void invalid() {
    throw new CommonException(ErrorCode.AI_INVALID_RESULT);
  }

  /** 모델이 전달한 식별자는 받지 않는다. 허용된 도구를 최대 세 번 실행하고 실제 전달한 근거만 기억한다. */
  private final class ReadSession implements ProductQuestionGateway.Tools {
    private final Target target;
    private final long userId;
    private final Snapshot initial;
    private final Set<String> called = new LinkedHashSet<>();
    private final Map<String, Evidence> delivered = new LinkedHashMap<>();
    private final StringBuilder deliveredJson = new StringBuilder();

    private ReadSession(Target target, long userId, Snapshot initial) {
      this.target = target;
      this.userId = userId;
      this.initial = initial;
    }

    @Override
    public Map<String, Object> read(String name) {
      if (!Set.of("get_product_specs", "get_product_history", "get_product_warranty").contains(name)
          || !called.add(name)
          || called.size() > 3) invalid();
      var current = sources.capture(target, userId);
      unchanged(initial, current);
      var product = current.data().path("product");
      Map<String, Object> payload;
      switch (name) {
        case "get_product_specs" -> {
          var facts = new LinkedHashMap<String, Object>();
          facts.put("productId", current.productId());
          facts.put("factsVersion", current.factsVersion());
          for (String field : List.of("manufacturer", "modelName", "specifications"))
            if (!product.path(field).isNull()) facts.put(field, product.path(field));
          var evidence = new Evidence("specs", "SPECS", "등록된 제품 사양", facts);
          delivered.put(evidence.key(), evidence);
          payload = Map.of("sourceKey", evidence.key(), "data", facts);
        }
        case "get_product_warranty" -> {
          var facts = new LinkedHashMap<String, Object>();
          facts.put("productId", current.productId());
          facts.put("asOf", current.asOf().toString());
          if (!product.path("warrantyExpiresAt").isNull()
              && !product.path("warrantyExpiresAt").isMissingNode()) {
            facts.put("warrantyExpiresOn", product.get("warrantyExpiresAt").asString());
            delivered.put("warranty", new Evidence("warranty", "WARRANTY", "등록된 보증 만료일", facts));
            payload = Map.of("sourceKey", "warranty", "data", facts);
          } else {
            // 실제 근거가 없는 경우 모델에도 인용할 키를 주지 않아 확인 불가 답변이 가짜 근거로 거부되지 않게 한다.
            payload =
                Map.of("data", facts, "note", "등록된 보증 만료일이 없어 인용할 근거가 없다. sourceKeys는 빈 배열로 답한다.");
          }
        }
        default -> {
          List<Map<String, Object>> events = new ArrayList<>();
          for (JsonNode event : current.data().path("history")) {
            long id = event.path("eventId").asLong();
            String key = "event:" + id;
            var facts = new LinkedHashMap<String, Object>();
            for (String field :
                List.of(
                    "eventId",
                    "type",
                    "occurredOn",
                    "status",
                    "previousOwner",
                    "corrected",
                    "detailsReviewed",
                    "title",
                    "content",
                    "replacedPart"))
              if (!event.path(field).isNull() && !event.path(field).isMissingNode())
                facts.put(field, event.get(field));
            // 정책 검사에서 쓰는 상태·검토 여부는 실제 타입으로 보관한다.
            facts.put("status", event.path("status").asString());
            facts.put("detailsReviewed", event.path("detailsReviewed").asBoolean());
            delivered.put(
                key,
                new Evidence(
                    key,
                    "EVENT",
                    event.path("occurredOn").asString() + " · " + event.path("type").asString(),
                    facts));
            events.add(Map.of("sourceKey", key, "data", facts));
          }
          payload =
              Map.of(
                  "events",
                  events,
                  "note",
                  "VOID는 실제 사건 집계에서 제외. detailsReviewed=false는 유형·날짜·상태만 확인 가능.");
        }
      }
      String value = payload.toString();
      textPolicy.validateInput(value);
      deliveredJson.append(value);
      return payload;
    }
  }
}
