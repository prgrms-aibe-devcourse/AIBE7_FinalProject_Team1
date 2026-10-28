package org.example.springtestci.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.example.springtestci.common.security.RequestRateLimiter;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/** 파일 업로드, 소유권 이전 수락, 채팅 전송처럼 반복 호출이 부담이 되는 쓰기 요청에 사용자별 횟수 제한을 적용한다. */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

  /** 제한 대상 요청과 한도. 한도는 사용자(로그인 전이면 IP) 단위로 센다. */
  record Rule(
      String bucket, HttpMethod method, String pathPattern, int maxRequests, Duration window) {}

  static final List<Rule> RULES =
      List.of(
          new Rule(
              "product-image-upload",
              HttpMethod.POST,
              "/api/v1/products/*/images",
              20,
              Duration.ofMinutes(10)),
          new Rule(
              "document-upload",
              HttpMethod.POST,
              "/api/v1/products/*/documents",
              20,
              Duration.ofMinutes(10)),
          new Rule(
              "document-replace",
              HttpMethod.PUT,
              "/api/v1/documents/*/file",
              20,
              Duration.ofMinutes(10)),
          new Rule(
              "chat-send",
              HttpMethod.POST,
              "/api/v1/chat-rooms/*/messages",
              30,
              Duration.ofMinutes(1)),
          new Rule("auth-signup", HttpMethod.POST, "/api/auth/signup", 10, Duration.ofMinutes(10)),
          new Rule(
              "auth-email-resend",
              HttpMethod.POST,
              "/api/auth/email/resend",
              5,
              Duration.ofMinutes(10)),
          new Rule(
              "ownership-verify",
              HttpMethod.GET,
              "/api/v1/ownership/transfers/verify",
              30,
              Duration.ofMinutes(1)),
          new Rule("auth-refresh", HttpMethod.POST, "/api/auth/refresh", 30, Duration.ofMinutes(1)),
          new Rule(
              "ownership-accept",
              HttpMethod.POST,
              "/api/v1/ownership/transfers/accept",
              10,
              Duration.ofMinutes(10)));

  private final AntPathMatcher pathMatcher = new AntPathMatcher();
  private final RequestRateLimiter rateLimiter;

  /** 횟수를 세는 제한기를 주입받는다. */
  public RateLimitInterceptor(RequestRateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  /** 요청이 제한 규칙에 해당하면 횟수를 세고, 한도를 넘으면 예외로 요청을 거부한다. */
  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    for (Rule rule : RULES) {
      if (rule.method().matches(request.getMethod())
          && pathMatcher.match(rule.pathPattern(), request.getRequestURI())) {
        rateLimiter.check(rule.bucket(), subject(request), rule.maxRequests(), rule.window());
        break;
      }
    }
    return true;
  }

  /** 인증된 사용자는 회원 ID로, 아니면 접속 IP로 집계한다. */
  private String subject(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
      return "user:" + jwt.getSubject();
    }
    return "ip:" + request.getRemoteAddr();
  }
}
