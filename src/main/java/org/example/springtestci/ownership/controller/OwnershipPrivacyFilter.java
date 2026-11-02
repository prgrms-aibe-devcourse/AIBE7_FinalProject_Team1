package org.example.springtestci.ownership.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 수신 화면·토큰 API의 성공/오류 응답 모두 캐시와 referrer 유출을 제한한다. 프록시 access log 정책을 대신하지 않는다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class OwnershipPrivacyFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (path.equals("/receive")
        || path.equals("/pages/receive.html")
        || path.startsWith("/api/v1/ownership/transfers")) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Referrer-Policy", "no-referrer");
    }
    chain.doFilter(request, response);
  }
}
