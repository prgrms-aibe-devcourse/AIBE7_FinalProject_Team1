package org.example.springtestci.common.security;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 배포(prod) 환경에서 로컬용 JWT 키나 Secure 없는 쿠키 설정이 섞여 들어오면 서버 시작을 막는다. */
@Component
@Profile("prod")
public class ProductionSecurityGuard {

  private static final String LOCAL_SECRET_PREFIX = "change-this";

  /** JWT 설정이 배포에 쓸 수 있는 값인지 검사한다. */
  public ProductionSecurityGuard(JwtProperties properties) {
    if (properties.secret().startsWith(LOCAL_SECRET_PREFIX)) {
      throw new IllegalStateException("배포 환경에서는 로컬용 JWT_SECRET 기본값을 쓸 수 없습니다.");
    }
    if (!properties.refreshCookieSecure()) {
      throw new IllegalStateException("배포 환경에서는 인증 쿠키에 Secure 설정이 필요합니다.");
    }
  }
}
