package org.example.springtestci.common.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

/** 관리자 HTML을 열기 전 인증이 끝났으면 공개 화면에서 세션 복구를 시작한다. 관리자 API의 401은 유지한다. */
final class AdminPageAuthenticationEntryPoint implements AuthenticationEntryPoint {
  private final AuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

  /** HTML 탐색만 고정된 복귀 경로로 안내한다. GET에서 토큰을 갱신하거나 관리자 HTML을 공개하지 않는다. */
  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
      throws IOException, ServletException {
    String path = request.getRequestURI();
    String accept = request.getHeader(HttpHeaders.ACCEPT);
    boolean adminPage =
        path.equals("/admin")
            || path.startsWith("/admin/")
            || path.equals("/admin.html")
            || path.equals("/pages/admin.html");
    if ("GET".equals(request.getMethod())
        && adminPage
        && accept != null
        && accept.contains("text/html")) {
      response.setStatus(HttpServletResponse.SC_FOUND);
      response.setHeader(HttpHeaders.LOCATION, request.getContextPath() + "/?returnTo=admin");
      response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      return;
    }
    bearer.commence(request, response, failure);
  }
}
