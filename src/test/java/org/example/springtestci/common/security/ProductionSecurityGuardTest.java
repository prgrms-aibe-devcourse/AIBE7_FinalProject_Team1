package org.example.springtestci.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProductionSecurityGuardTest {

  private static JwtProperties properties(String secret, boolean secureCookie) {
    return new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(14), secureCookie);
  }

  /** 운영용 키와 Secure 쿠키면 통과한다. */
  @Test
  void acceptsAProductionConfiguration() {
    assertDoesNotThrow(
        () ->
            new ProductionSecurityGuard(
                properties("prod-secret-prod-secret-prod-secret-12", true)));
  }

  /** 로컬용 기본 키를 배포에 쓰면 시작을 막는다. */
  @Test
  void rejectsTheLocalDefaultSecret() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new ProductionSecurityGuard(
                properties("change-this-local-jwt-secret-at-least-32-bytes", true)));
  }

  /** Secure 없는 인증 쿠키는 배포에서 막는다. */
  @Test
  void rejectsInsecureCookies() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new ProductionSecurityGuard(
                properties("prod-secret-prod-secret-prod-secret-12", false)));
  }
}
