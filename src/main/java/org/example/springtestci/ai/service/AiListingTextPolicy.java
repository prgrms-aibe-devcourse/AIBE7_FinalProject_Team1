package org.example.springtestci.ai.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 형식·개인정보·명백한 금지 표현을 검사하는 보조 방어. 의미상 정확성은 판매자의 확인도 필요하다. */
@Component
public class AiListingTextPolicy {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern SUGGESTION_DATE =
      Pattern.compile(
          "(?<!\\d)(?:(\\d{4})\\s*[-./]\\s*(\\d{1,2})\\s*[-./]\\s*(\\d{1,2})|"
              + "(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일)(?!\\d)");
  private static final Pattern UNSAFE =
      Pattern.compile(
          "(?i)(https?://|www\\.|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}|"
              + "0\\d{1,2}[- .]?\\d{3,4}[- .]?\\d{4}|\\d{6}[- ]?[1-4]\\d{6}|"
              + "\\d[\\d,]*\\s*원|시리얼|serial|증빙|영수증|보증서|계좌|연락처|주소|주문번호|[<>]|"
              + "[\\p{Cc}&&[^\\n\\r\\t]]|[\\u202a-\\u202e\\u2066-\\u2069])");
  private static final Pattern CLAIM =
      Pattern.compile(
          "(?i)(실제\\s*사용|실사용|무고장|무하자|수리\\s*(이력|기록|한\\s*적)?\\s*(없|무)|"
              + "고장\\s*(이력|기록|난\\s*적)?\\s*없|정품\\s*(보장|인증|확실)|"
              + "(품질|안전|성능)\\s*보장|구매\\s*(추천|비추천)|가성비|최저가|"
              + "never\\s*(repaired|broken)|guaranteed|fault[- ]free)");
  private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

  /** AI에 보내기 전 공개 자료에 연락처·시리얼 등 금지 정보가 섞여 있는지 보조 검사한다. */
  public void validateInput(String json) {
    if (UNSAFE.matcher(json).find()) throw new CommonException(ErrorCode.AI_SOURCE_UNAVAILABLE);
  }

  /** 판매글 제안의 날짜만 실제 원천과 대조해 ISO 표기로 맞춘다. 날짜 밖의 숫자·안전 검사는 그대로 적용한다. */
  public ListingSuggestionGateway.Suggestion validateSuggestion(
      ListingSuggestionGateway.Suggestion suggestion, String source) {
    if (suggestion == null) invalid();
    Set<String> dates = registeredSuggestionDates(source);
    return validate(
        new ListingSuggestionGateway.Suggestion(
            normalizeSuggestionDates(suggestion.title(), dates),
            normalizeSuggestionDates(suggestion.description(), dates)),
        source);
  }

  private Set<String> registeredSuggestionDates(String source) {
    try {
      JsonNode input = JSON.readTree(source);
      if (!input.isObject()) throw new CommonException(ErrorCode.AI_SOURCE_UNAVAILABLE);
      Set<String> dates = new HashSet<>();
      addSuggestionDate(dates, input.path("product").path("purchaseDate"));
      addSuggestionDate(dates, input.path("product").path("warrantyExpiresAt"));
      for (var event : input.path("history")) addSuggestionDate(dates, event.path("occurredOn"));
      return dates;
    } catch (RuntimeException failure) {
      throw new CommonException(ErrorCode.AI_SOURCE_UNAVAILABLE);
    }
  }

  private void addSuggestionDate(Set<String> dates, JsonNode value) {
    if (value.isString()) dates.add(value.asString());
  }

  // 숫자를 일괄 변환하지 않는다. 등록된 날짜 전체가 일치해야 하므로 05→5를 허용해 5GB까지 통과시키지 않는다.
  private String normalizeSuggestionDates(String text, Set<String> registered) {
    if (text == null) return null;
    var matcher = SUGGESTION_DATE.matcher(text);
    var normalized = new StringBuilder();
    while (matcher.find()) {
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
      if (!registered.contains(date)) invalid();
      matcher.appendReplacement(normalized, Matcher.quoteReplacement(date));
    }
    return matcher.appendTail(normalized).toString();
  }

  /** 생성된 제목·본문의 길이·형식과 금지 표현을 확인한 뒤 화면에서 사용할 텍스트를 반환한다. */
  public ListingSuggestionGateway.Suggestion validate(
      ListingSuggestionGateway.Suggestion suggestion, String source) {
    if (suggestion == null) invalid();
    String title = suggestion.title() == null ? "" : suggestion.title().strip();
    String description = suggestion.description() == null ? "" : suggestion.description().strip();
    if (title.isBlank()
        || title.length() > 60
        || title.contains("\n")
        || title.contains("\r")
        || description.isBlank()
        || description.length() > 2000) invalid();
    String text = title + "\n" + description;
    if (UNSAFE.matcher(text).find() || CLAIM.matcher(text).find()) invalid();
    // 입력에 없던 숫자가 추가된 결과를 거부한다. 숫자가 같아도 문장의 의미가 맞는지는 별도 확인이 필요하다.
    Set<String> allowed = numbers(source);
    if (!allowed.containsAll(numbers(text))) invalid();
    return new ListingSuggestionGateway.Suggestion(title, description);
  }

  private Set<String> numbers(String source) {
    Set<String> values = new HashSet<>();
    var matches = NUMBER.matcher(source);
    while (matches.find()) values.add(matches.group());
    return values;
  }

  private void invalid() {
    throw new CommonException(ErrorCode.AI_INVALID_RESULT);
  }
}
