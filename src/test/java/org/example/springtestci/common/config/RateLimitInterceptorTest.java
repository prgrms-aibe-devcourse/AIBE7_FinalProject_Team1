package org.example.springtestci.common.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.example.springtestci.common.security.RequestRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

  @Mock private RequestRateLimiter rateLimiter;

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  /** 채팅 전송은 로그인한 회원 ID 기준으로 센다. */
  @Test
  void limitsChatSendPerUser() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("7").build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    new RateLimitInterceptor(rateLimiter)
        .preHandle(
            new MockHttpServletRequest("POST", "/api/v1/chat-rooms/3/messages"),
            new MockHttpServletResponse(),
            new Object());

    verify(rateLimiter).check("chat-send", "user:7", 30, Duration.ofMinutes(1));
  }

  /** 인증 정보가 없으면 접속 IP 기준으로 센다. */
  @Test
  void fallsBackToRemoteAddressWithoutAuthentication() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/products/1/documents");
    request.setRemoteAddr("10.0.0.9");

    new RateLimitInterceptor(rateLimiter)
        .preHandle(request, new MockHttpServletResponse(), new Object());

    verify(rateLimiter).check("document-upload", "ip:10.0.0.9", 20, Duration.ofMinutes(10));
  }

  /** 제한 대상이 아닌 요청은 세지 않는다. */
  @Test
  void ignoresRequestsWithoutARule() {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter);

    interceptor.preHandle(
        new MockHttpServletRequest("GET", "/api/v1/chat-rooms/3/messages"),
        new MockHttpServletResponse(),
        new Object());
    interceptor.preHandle(
        new MockHttpServletRequest("POST", "/api/v1/products"),
        new MockHttpServletResponse(),
        new Object());

    verify(rateLimiter, never()).check(anyString(), anyString(), anyInt(), any());
  }

  /** 회원가입과 토큰 갱신은 로그인 전이라 접속 IP 기준으로 센다. */
  @Test
  void limitsSignupAndRefreshByIp() {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter);
    MockHttpServletRequest signup = new MockHttpServletRequest("POST", "/api/auth/signup");
    signup.setRemoteAddr("10.0.0.3");
    MockHttpServletRequest refresh = new MockHttpServletRequest("POST", "/api/auth/refresh");
    refresh.setRemoteAddr("10.0.0.3");

    interceptor.preHandle(signup, new MockHttpServletResponse(), new Object());
    interceptor.preHandle(refresh, new MockHttpServletResponse(), new Object());

    verify(rateLimiter).check("auth-signup", "ip:10.0.0.3", 10, Duration.ofMinutes(10));
    verify(rateLimiter).check("auth-refresh", "ip:10.0.0.3", 30, Duration.ofMinutes(1));
  }

  /** 소유권 이전 수락도 규칙에 걸린다. */
  @Test
  void limitsOwnershipAccept() {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter);
    MockHttpServletRequest accept =
        new MockHttpServletRequest("POST", "/api/v1/ownership/transfers/accept");
    accept.setRemoteAddr("10.0.0.2");

    interceptor.preHandle(accept, new MockHttpServletResponse(), new Object());

    verify(rateLimiter).check("ownership-accept", "ip:10.0.0.2", 10, Duration.ofMinutes(10));
  }

  /** 제품 이미지 업로드와 문서 교체도 규칙에 걸린다. */
  @Test
  void limitsImageUploadAndDocumentReplace() {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter);
    MockHttpServletRequest image = new MockHttpServletRequest("POST", "/api/v1/products/1/images");
    image.setRemoteAddr("10.0.0.1");
    MockHttpServletRequest replace = new MockHttpServletRequest("PUT", "/api/v1/documents/2/file");
    replace.setRemoteAddr("10.0.0.1");

    interceptor.preHandle(image, new MockHttpServletResponse(), new Object());
    interceptor.preHandle(replace, new MockHttpServletResponse(), new Object());

    verify(rateLimiter)
        .check(eq("product-image-upload"), eq("ip:10.0.0.1"), eq(20), any(Duration.class));
    verify(rateLimiter)
        .check(eq("document-replace"), eq("ip:10.0.0.1"), eq(20), any(Duration.class));
  }
}
