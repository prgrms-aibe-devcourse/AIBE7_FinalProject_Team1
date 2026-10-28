package org.example.springtestci.ai.service;

import java.util.regex.Pattern;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/** SEC-05, SEC-06 AI 지시문 및 도구 호출 보안 검증기 */
@Component
public class AiSecurityValidator {

  // 시스템 명령이나 권한 상승, 프롬프트 인젝션 패턴
  private static final Pattern INJECTION_PATTERN =
      Pattern.compile(
          "(?i)(ignore\\s+(all\\s+)?previous\\s+instructions|system\\s+prompt|admin\\s+override|grant\\s+permission|소유권\\s*이전\\s*실행|관리자\\s*권한\\s*탈취)");

  /** 사용자 입력 프롬프트 인젝션 검증 */
  public void validateUserInput(String input) {
    if (input == null || input.isBlank()) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    if (INJECTION_PATTERN.matcher(input).find()) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
  }

  /** AI 도구 호출 제한 검증 (SEC-06: 허용된 조회 기능만 허용) */
  public void validateToolExecution(AiToolType toolType) {
    if (toolType == null || !toolType.isAllowed()) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
  }

  /** XSS 방어를 위한 안전한 텍스트 이스케이프 (SEC-05) */
  public String sanitizeText(String text) {
    if (text == null) return null;
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#x27;");
  }
}
