package org.example.springtestci.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CookieBearerTokenFilterTest {

  private final CookieBearerTokenFilter filter = new CookieBearerTokenFilter();

  /** API 요청의 Access Token 쿠키를 Authorization 헤더로 바꿔 넘긴다. */
  @Test
  void turnsTheAccessCookieIntoABearerHeaderForApiRequests() throws Exception {
    MockHttpServletRequest request = request("GET", "/api/auth/me");
    request.setCookies(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, "abc"));

    assertEquals("Bearer abc", forwarded(request).getHeader(HttpHeaders.AUTHORIZATION));
  }

  /** 이미 Authorization 헤더가 있으면 쿠키로 덮어쓰지 않는다. */
  @Test
  void keepsAnExplicitAuthorizationHeader() throws Exception {
    MockHttpServletRequest request = request("GET", "/api/auth/me");
    request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer header-token");
    request.setCookies(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, "cookie-token"));

    assertEquals("Bearer header-token", forwarded(request).getHeader(HttpHeaders.AUTHORIZATION));
  }

  /** 화면과 정적 파일은 잘못된 쿠키가 남아 있어도 인증을 시도하지 않는다. */
  @Test
  void ignoresTheCookieOutsideApiPaths() throws Exception {
    MockHttpServletRequest request = request("GET", "/pages/market.html");
    request.setCookies(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, "expired"));

    assertNull(forwarded(request).getHeader(HttpHeaders.AUTHORIZATION));
  }

  /** 로그인·갱신·로그아웃은 만료된 쿠키가 남아 있어도 막히지 않는다. */
  @Test
  void ignoresTheCookieForAuthPostRequests() throws Exception {
    for (String path : new String[] {"/api/auth/login", "/api/auth/refresh", "/api/auth/logout"}) {
      MockHttpServletRequest request = request("POST", path);
      request.setCookies(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, "expired"));

      assertNull(forwarded(request).getHeader(HttpHeaders.AUTHORIZATION), path);
    }
  }

  /** 공개 판매글 목록은 만료된 쿠키가 있어도 비회원으로 조회된다. */
  @Test
  void ignoresTheCookieForPublicListingReads() throws Exception {
    MockHttpServletRequest list = request("GET", "/api/v1/listings");
    list.setCookies(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, "expired"));
    MockHttpServletRequest thumbnail = request("GET", "/api/v1/listings/3/thumbnail");
    thumbnail.setCookies(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, "expired"));

    assertNull(forwarded(list).getHeader(HttpHeaders.AUTHORIZATION));
    assertNull(forwarded(thumbnail).getHeader(HttpHeaders.AUTHORIZATION));
  }

  /** 쿠키가 없거나 비어 있으면 헤더를 만들지 않는다. */
  @Test
  void doesNothingWithoutACookieValue() throws Exception {
    MockHttpServletRequest none = request("GET", "/api/auth/me");
    MockHttpServletRequest blank = request("GET", "/api/auth/me");
    blank.setCookies(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, " "));

    assertNull(forwarded(none).getHeader(HttpHeaders.AUTHORIZATION));
    assertNull(forwarded(blank).getHeader(HttpHeaders.AUTHORIZATION));
  }

  private static MockHttpServletRequest request(String method, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRequestURI(uri);
    return request;
  }

  /** 필터를 거쳐 다음 단계가 받은 요청을 돌려준다. */
  private HttpServletRequest forwarded(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return (HttpServletRequest) chain.getRequest();
  }
}
