package org.example.springtestci.listing.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 이력의 공개용 문자열을 자동으로 제한한다. 개인정보·비용·파일 참조가 감지된 필드 전체를 제외하고 원본은 보존한다. ADMIN 승인이나 외부 AI 호출 없이 적용하며,
 * 자유문장에 숨은 모든 이름·주소를 식별하는 기능은 아니다.
 */
public final class PublicHistoryTextPolicy {
  // 모두 컴파일 시 정해진 상수다. 입력 길이는 clean에서 제한하고 구분자에는 역추적이 없는 반복을 쓴다.
  private static final String NUMBER_SEPARATOR = "[\\s\\p{Z}\\p{Pd}\\u2212.]*+";
  private static final Pattern EXCLUDED =
      Pattern.compile(
          "(?i)(?:https?://|www\\.|[^\\s@]+@[^\\s@]+\\.[^\\s@]+|"
              + "(?<!\\d)(?:0\\d{1,2}"
              + NUMBER_SEPARATOR
              + "\\d{3,4}"
              + NUMBER_SEPARATOR
              + "\\d{4}|\\+\\d[\\d\\s\\p{Z}\\p{Pd}\\u2212().]{7,}\\d|\\d{6}"
              + NUMBER_SEPARATOR
              + "[1-4]\\d{6}|\\d{7,}|(?:\\d[\\s\\p{Z}\\p{Pd}\\u2212]*+){13,19})(?!\\d)|"
              + "\\d[\\d,.]*\\s*(?:원|만원|천원|KRW|USD|달러)|[₩$€£]\\s*\\d|"
              + "수리\\s*비|구매\\s*금액|비용|금액|\\b(?:cost|price|amount)\\b|"
              + "시리얼|serial|\\bs/?n\\s*[:#]|증빙|영수증|보증서|계좌|연락처|전화|휴대폰|주소|주문\\s*번호|"
              + "실명|성명|고객\\s*명|고객\\s*이름|담당\\s*(?:자|기사)|예금주|수령인|수취인|"
              + "(?:소유자|구매자|판매자)\\s*[:：]|[가-힣]{2,4}\\s*(?:씨|님)(?:\\s|[,.]|$)|"
              + "\\b(?:email|phone|address|account|receipt|invoice|customer\\s*name|owner\\s*name|technician)\\b|"
              + "[가-힣]+(?:시|군|구)\\s+[가-힣0-9]+(?:로|길|동)|[가-힣0-9]+(?:로|길)\\s*\\d+|"
              + "[<>]|[\\p{Cc}&&[^\\n\\r\\t]]|\\p{Cf})");

  private PublicHistoryTextPolicy() {}

  /** 공백과 호환 문자를 정리한 뒤 필드별로 검사한다. 위험한 일부만 지우고 나머지가 안전하다고 가정하지 않는다. */
  public static String clean(String value, int maxLength) {
    if (value == null || value.isBlank()) return null;
    if (value.length() > maxLength) return null;
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
    if (normalized.length() > maxLength || EXCLUDED.matcher(normalized).find()) return null;
    return normalized;
  }
}
