package org.example.springtestci.ai.service;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.example.springtestci.ai.entity.ListingSummaryState;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.service.PublicHistoryTextPolicy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 문장 형식·규칙 설명·제품 사실 참조와 공통 안전 검사를 적용한다. 모든 문장의 의미를 보장하는 검사는 아니다. */
@Component
public class ListingSummaryTextPolicy {
  private static final Pattern MULTIPLE = Pattern.compile("[.!?。！？]\\s+\\S");
  private static final Pattern DATE =
      Pattern.compile(
          "(?<!\\d)(?:(\\d{4})-(\\d{1,2})-(\\d{1,2})|"
              + "(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일)(?!\\d)");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  // 'VOID는 수리 사실로 단정하지 않는다'처럼 제품 설명 대신 작성 규칙을 반복하는 결과를 거부한다.
  private static final Pattern PROCESS_INSTRUCTIONS =
      Pattern.compile(
          "(?i)(프롬프트|지시문|시스템\\s*지침|작성\\s*(규칙|지침)|출력\\s*(형식|규칙)|"
              + "sellerReportedGrade|productAndHistory|detailsReviewed|sourceVersion|factsVersion|"
              + "(?<![A-Za-z0-9_])(VOID|VALID|REVIEWED|REVIEW_PENDING|JSON|sentences)(?![A-Za-z0-9_])|"
              + "검토하되|(?:확인|설명|반영)하도록\\s*덧붙|"
              + "(?:정정[·\\s]*)?무효\\s*표시를?\\s*유지|유효\\s*사실을\\s*기준으로\\s*설명|"
              + "(?:타임라인|전체\\s*기록)\\s*확인을\\s*안내(?:한다|합니다))");
  private final AiListingTextPolicy common;

  public ListingSummaryTextPolicy(AiListingTextPolicy common) {
    this.common = common;
  }

  /** 카드에서 읽을 1~2문장·90자 이내 요약인지 검사하고 날짜 표기와 안전 검사를 함께 적용한다. */
  public String validate(List<String> values, String input) {
    if (values == null || values.isEmpty() || values.size() > 2) invalid();
    Set<String> dates = registeredDates(input);
    var unique = new HashSet<String>();
    List<String> sentences = new ArrayList<>();
    for (String value : values) {
      if (value == null
          || value.isBlank()
          || value.length() > ListingSummaryState.CARD_MAX_LENGTH
          || value.contains("\n")
          || value.contains("\r")) invalid();
      // 날짜 표기를 먼저 맞춰 같은 내용을 다른 날짜 표기로 반복한 문장도 중복으로 잡는다.
      String sentence = normalizeDates(value.strip(), dates);
      if (!sentence.matches("(?s).*[.!?。！？]$")
          || MULTIPLE.matcher(sentence).find()
          || !unique.add(sentence)) invalid();
      sentences.add(sentence);
    }
    String text = String.join(" ", sentences);
    if (text.length() > ListingSummaryState.CARD_MAX_LENGTH
        || PublicHistoryTextPolicy.clean(text, ListingSummaryState.CARD_MAX_LENGTH) == null
        || containsProcessInstructions(text)
        || !referencesProductFact(text, input)) invalid();
    // 제목·본문 제안과 같은 개인정보·금지 표현·미등록 숫자 검사도 요약에 재사용한다.
    return common
        .validate(new ListingSuggestionGateway.Suggestion("제품 상태 요약", text), input)
        .description();
  }

  // 이전 버전에서 저장된 규칙 설명도 조회 시 숨기되, 이 검사 자체로 AI를 다시 호출하지 않는다.
  boolean containsProcessInstructions(String text) {
    return text != null && PROCESS_INSTRUCTIONS.matcher(text).find();
  }

  /** 저장된 카드에도 현재 공개 제외 규칙을 적용한다. 원문 사실 검증이나 AI 호출을 새로 실행하지 않는다. */
  public boolean reusableStored(String text) {
    return text != null
        && !text.isBlank()
        && text.length() <= ListingSummaryState.CARD_MAX_LENGTH
        && PublicHistoryTextPolicy.clean(text, ListingSummaryState.CARD_MAX_LENGTH) != null
        && !text.contains("\n")
        && !text.contains("\r")
        && text.matches("(?s).*[.!?。！？]$")
        && text.split("(?<=[.!?。！？])\\s+").length <= 2
        && !containsProcessInstructions(text);
  }

  // 허용할 날짜의 근거는 등록된 구매일·보증 만료일·이력 발생일에서만 모은다.
  private Set<String> registeredDates(String input) {
    JsonNode source;
    try {
      source = JSON.readTree(input).path("productAndHistory");
    } catch (RuntimeException failure) {
      throw new CommonException(ErrorCode.AI_INVALID_RESULT);
    }
    Set<String> dates = new HashSet<>();
    addDate(dates, source.path("product").path("purchaseDate"));
    addDate(dates, source.path("product").path("warrantyExpiresAt"));
    for (var event : source.path("history")) addDate(dates, event.path("occurredOn"));
    return dates;
  }

  private void addDate(Set<String> dates, JsonNode value) {
    if (value.isString()) dates.add(value.asString());
  }

  // 같은 날짜의 '02'와 '2'를 다른 숫자로 오인하지 않도록 등록된 YYYY-MM-DD 표기로 맞춘다.
  // 등록되지 않았거나 달력에 존재하지 않는 날짜는 거부하며, 날짜 밖의 숫자 검사는 그대로 유지한다.
  private String normalizeDates(String sentence, Set<String> dates) {
    var matcher = DATE.matcher(sentence);
    var normalized = new StringBuilder();
    while (matcher.find()) {
      // 앞쪽 정규식 그룹은 ISO 날짜, 뒤쪽 그룹은 한글 날짜의 연·월·일이다.
      int first = matcher.group(1) != null ? 1 : 4;
      String date;
      try {
        date =
            LocalDate.of(
                    Integer.parseInt(matcher.group(first)),
                    Integer.parseInt(matcher.group(first + 1)),
                    Integer.parseInt(matcher.group(first + 2)))
                .toString();
      } catch (DateTimeException failure) {
        throw new CommonException(ErrorCode.AI_INVALID_RESULT);
      }
      if (!dates.contains(date)) invalid();
      matcher.appendReplacement(normalized, Matcher.quoteReplacement(date));
    }
    return matcher.appendTail(normalized).toString();
  }

  // 등급·일반 안내만 있는 요약을 막기 위해 실제 모델명이나 등록 사양이 하나 이상 포함됐는지 확인한다.
  private boolean referencesProductFact(String text, String input) {
    JsonNode product;
    try {
      product = JSON.readTree(input).path("productAndHistory").path("product");
    } catch (RuntimeException failure) {
      return false;
    }
    if (!product.isObject()) return false;
    var specifications = product.path("specifications");
    List<String> facts = new ArrayList<>();
    for (var value :
        List.of(
            product.path("modelName"),
            specifications.path("cpu"),
            specifications.path("gpu"),
            specifications.path("display"),
            specifications.path("os"))) {
      if (value.isString() && value.asString().strip().length() >= 2) facts.add(value.asString());
    }
    addMeasurement(facts, specifications.path("ramGb"), "GB");
    addMeasurement(facts, specifications.path("storageGb"), "GB");
    addMeasurement(facts, specifications.path("batteryHealthPercent"), "%");
    if (facts.isEmpty() && product.path("manufacturer").isString())
      facts.add(product.path("manufacturer").asString());
    // 자료가 부족할 때 가상의 사양을 요구하지 않는다. 일부 사실의 포함 여부만 확인하는 보조 검사다.
    String normalized = normalize(text);
    return facts.isEmpty()
        || facts.stream()
            .map(this::normalize)
            .filter(value -> !value.isBlank())
            .anyMatch(normalized::contains);
  }

  private void addMeasurement(List<String> facts, JsonNode value, String unit) {
    if (value.isIntegralNumber() && value.asLong() > 0) facts.add(value.asLong() + unit);
  }

  // '18 GB'와 '18GB', 'MacBook'과 'macbook'을 같은 정보로 비교하도록 문자 표기를 맞춘다.
  private String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\s\\p{Z}]+", "");
  }

  private void invalid() {
    throw new CommonException(ErrorCode.AI_INVALID_RESULT);
  }
}
