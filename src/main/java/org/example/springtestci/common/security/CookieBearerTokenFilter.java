package org.example.springtestci.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * HttpOnly Access Token 쿠키를 Authorization 헤더로 바꿔 resource server에 넘긴다. 반드시 CsrfFilter 뒤에 둔다.
 * Spring은 Bearer 토큰이 있는 요청을 CSRF 검사에서 빼므로, 쿠키를 리졸버로 직접 읽게 하면 쿠키 요청도 검사를 건너뛰게 된다.
 */
class CookieBearerTokenFilter extends OncePerRequestFilter {
  private final JwtDecoder decoder;
  private final JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

  CookieBearerTokenFilter(JwtDecoder decoder) {
    this.decoder = decoder;
  }

  /** 쿠키에 토큰이 있으면 Authorization 헤더가 있는 요청으로 바꿔 다음 필터에 넘긴다. */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = cookieToken(request);
    // 공개 목록도 정상 쿠키는 회원으로 인식한다. 만료·변조·로그아웃된 쿠키는 비회원 공개 범위로 처리한다.
    // 명시적 Authorization 요청은 여기서 처리하지 않고 기존 resource server의 엄격한 검증을 따른다.
    if (token != null
        && "GET".equals(request.getMethod())
        && request.getRequestURI().equals("/api/v1/listings")) {
      try {
        var authentication = converter.convert(decoder.decode(token));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
      } catch (JwtException ignored) {
        // 같은 디코더의 서명·만료·로그아웃 차단 검증을 통과하지 못하면 회원 요약을 공개하지 않는다.
      }
      chain.doFilter(request, response);
      return;
    }
    chain.doFilter(token == null ? request : new BearerRequest(request, token), response);
  }

  /** API와 보호된 관리자 HTML에만 쿠키 인증을 적용한다. 일반 공개 화면은 만료된 쿠키로 막지 않는다. */
  private static String cookieToken(HttpServletRequest request) {
    if (request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
      return null;
    }
    String uri = request.getRequestURI();
    boolean adminPage =
        uri.equals("/admin")
            || uri.startsWith("/admin/")
            || uri.equals("/admin.html")
            || uri.equals("/pages/admin.html");
    if (!uri.startsWith("/api/") && !adminPage) {
      return null;
    }
    // 대표 사진은 회원별 응답이 없으므로 로그인 쿠키 없이 공개 범위만 조회한다.
    if ("GET".equals(request.getMethod())
        && request.getRequestURI().matches("/api/v1/listings/[^/]+/thumbnail")) {
      return null;
    }
    // 로그인·갱신·로그아웃은 만료되거나 잘못된 쿠키가 남아 있어도 막히지 않아야 한다.
    if ("POST".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/auth/")) {
      return null;
    }
    Cookie cookie = WebUtils.getCookie(request, SecurityConfig.ACCESS_TOKEN_COOKIE);
    return cookie == null || cookie.getValue().isBlank() ? null : cookie.getValue();
  }

  private static class BearerRequest extends HttpServletRequestWrapper {

    private final String authorization;

    /** 쿠키에서 꺼낸 토큰으로 Authorization 헤더를 덧붙인 요청을 만든다. */
    BearerRequest(HttpServletRequest request, String token) {
      super(request);
      this.authorization = "Bearer " + token;
    }

    /** Authorization 헤더는 쿠키에서 만든 값으로 응답한다. */
    @Override
    public String getHeader(String name) {
      return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
          ? authorization
          : super.getHeader(name);
    }

    /** Authorization 헤더 값 목록에 쿠키에서 만든 값을 쓴다. */
    @Override
    public Enumeration<String> getHeaders(String name) {
      return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
          ? Collections.enumeration(Set.of(authorization))
          : super.getHeaders(name);
    }

    /** 헤더 이름 목록에 Authorization을 포함한다. */
    @Override
    public Enumeration<String> getHeaderNames() {
      Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
      names.add(HttpHeaders.AUTHORIZATION);
      return Collections.enumeration(names);
    }
  }
}
