package org.example.springtestci.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.example.springtestci.common.security.RequestRateLimiter;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/** 파일 업로드, AI 호출, 소유권 이전 수락, 채팅 전송처럼 반복 호출이 부담이 되는 쓰기 요청에 사용자별 횟수 제한을 적용한다. */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

  /** 제한 대상 요청과 한도. 한도는 사용자(로그인 전이면 IP) 단위로 센다. */
  record Rule(
      String bucket, HttpMethod method, PathPattern pathPattern, int maxRequests, Duration window) {
    Rule(String bucket, HttpMethod method, String pathPattern, int maxRequests, Duration window) {
      this(
          bucket,
          method,
          PathPatternParser.defaultInstance.parse(pathPattern),
          maxRequests,
          window);
    }
  }

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
          new Rule("ai-ask", HttpMethod.POST, "/api/v1/ai/ask", 10, Duration.ofMinutes(1)),
          new Rule(
              "ai-estimate-price",
              HttpMethod.POST,
              "/api/v1/ai/estimate-price",
              10,
              Duration.ofMinutes(1)),
          new Rule(
              "ownership-accept",
              HttpMethod.POST,
              "/api/v1/ownership/transfers/accept",
              10,
              Duration.ofMinutes(10)),
          // 제품/판매글이나 대상 ID를 바꿔도 같은 회원별 질문 한도를 공유한다.
          new Rule(
              "product-question",
              HttpMethod.POST,
              "/api/v1/products/*/ai/questions",
              10,
              Duration.ofMinutes(1)),
          new Rule(
              "product-question",
              HttpMethod.POST,
              "/api/v1/listings/*/ai/questions",
              10,
              Duration.ofMinutes(1)));

  private final RequestRateLimiter rateLimiter;

  /** 횟수를 세는 제한기를 주입받는다. */
  public RateLimitInterceptor(RequestRateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  /** 요청이 제한 규칙에 해당하면 횟수를 세고, 한도를 넘으면 예외로 요청을 거부한다. */
  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    // 인코딩·컨텍스트 경로로 제한을 우회하지 못하도록 MVC와 같은 경로 해석을 사용한다.
    PathContainer path =
        (ServletRequestPathUtils.hasParsedRequestPath(request)
                ? ServletRequestPathUtils.getParsedRequestPath(request)
                : ServletRequestPathUtils.parseAndCache(request))
            .pathWithinApplication();
    for (Rule rule : RULES) {
      if (rule.method().matches(request.getMethod()) && rule.pathPattern().matches(path)) {
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
