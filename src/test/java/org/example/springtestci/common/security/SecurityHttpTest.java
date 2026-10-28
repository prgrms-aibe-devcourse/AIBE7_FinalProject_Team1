package org.example.springtestci.common.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 실제 보안 필터 체인(CSRF, 쿠키 인증, 접근 규칙)에 요청을 보내 차단 동작을 확인한다. */
@SpringJUnitWebConfig(SecurityHttpTest.TestConfig.class)
class SecurityHttpTest {

  private static final String SECRET = "http-test-secret-http-test-secret-1234";
  private static final JwtProperties PROPERTIES =
      new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(14), true);
  private static final AccessTokenBlocklist BLOCKLIST = mock(AccessTokenBlocklist.class);

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  static class TestConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
      return new SecurityConfig().securityFilterChain(http);
    }

    @Bean
    JwtDecoder jwtDecoder() {
      return new SecurityConfig().jwtDecoder(PROPERTIES, BLOCKLIST);
    }

    @Bean
    Probe probe() {
      return new Probe();
    }
  }

  /** 보안 규칙만 확인하려는 가짜 API. */
  @RestController
  static class Probe {

    @GetMapping("/api/v1/probe")
    String read() {
      return "ok";
    }

    @PostMapping("/api/v1/probe")
    String write() {
      return "ok";
    }

    @PostMapping("/api/auth/login")
    String login() {
      return "ok";
    }
  }

  @Autowired private WebApplicationContext context;
  private MockMvc mvc;

  /** 테스트마다 보안 필터를 붙인 MockMvc를 만든다. */
  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
            .build();
  }

  /** 로그인한 쿠키가 있어도 CSRF 토큰이 없는 쓰기 요청은 차단한다. */
  @Test
  void rejectsACookieAuthenticatedWriteWithoutCsrfToken() throws Exception {
    mvc.perform(post("/api/v1/probe").cookie(accessCookie("jti-1")))
        .andExpect(status().isForbidden());
  }

  /** CSRF 쿠키와 헤더 값이 다르면 차단한다. */
  @Test
  void rejectsAWriteWhenTheCsrfHeaderDoesNotMatchTheCookie() throws Exception {
    mvc.perform(
            post("/api/v1/probe")
                .cookie(accessCookie("jti-1"), new Cookie("XSRF-TOKEN", "right"))
                .header("X-XSRF-TOKEN", "wrong"))
        .andExpect(status().isForbidden());
  }

  /** CSRF 쿠키와 헤더 값이 같으면 쿠키 인증 쓰기 요청을 허용한다. */
  @Test
  void acceptsACookieAuthenticatedWriteWithAMatchingCsrfToken() throws Exception {
    mvc.perform(
            post("/api/v1/probe")
                .cookie(accessCookie("jti-1"), new Cookie("XSRF-TOKEN", "same"))
                .header("X-XSRF-TOKEN", "same"))
        .andExpect(status().isOk());
  }

  /** 비로그인 요청도 CSRF 토큰 없이는 로그인 같은 공개 쓰기 API를 쓸 수 없다. */
  @Test
  void rejectsAPublicWriteWithoutCsrfToken() throws Exception {
    mvc.perform(post("/api/auth/login")).andExpect(status().isForbidden());
  }

  /** Authorization 헤더로 인증하는 API 클라이언트는 CSRF 검사에서 제외된다. */
  @Test
  void letsABearerHeaderClientWriteWithoutCsrfToken() throws Exception {
    mvc.perform(post("/api/v1/probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("jti-2")))
        .andExpect(status().isOk());
  }

  /** 인증 정보가 없으면 보호된 API는 401이다. */
  @Test
  void rejectsAProtectedReadWithoutCredentials() throws Exception {
    mvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized());
  }

  /** 서명이 맞지 않는 쿠키 토큰은 401이다. */
  @Test
  void rejectsAForgedAccessCookie() throws Exception {
    mvc.perform(
            get("/api/v1/probe").cookie(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, "forged")))
        .andExpect(status().isUnauthorized());
  }

  /** 만료된 토큰은 401이다. */
  @Test
  void rejectsAnExpiredAccessCookie() throws Exception {
    mvc.perform(
            get("/api/v1/probe")
                .cookie(accessCookie(token("jti-3", Instant.now().minusSeconds(60)))))
        .andExpect(status().isUnauthorized());
  }

  /** 로그아웃한 토큰은 유효 기간이 남아 있어도 401이다. */
  @Test
  void rejectsALoggedOutAccessCookie() throws Exception {
    when(BLOCKLIST.isBlocked("jti-out")).thenReturn(true);

    mvc.perform(get("/api/v1/probe").cookie(accessCookie("jti-out")))
        .andExpect(status().isUnauthorized());
  }

  /** 유효한 쿠키 토큰이면 조회 API를 이용할 수 있다. */
  @Test
  void acceptsAValidAccessCookieForReads() throws Exception {
    mvc.perform(get("/api/v1/probe").cookie(accessCookie("jti-ok"))).andExpect(status().isOk());
  }

  /** 배포용 HTTPS 응답에만 HSTS 헤더가 붙는다. */
  @Test
  void sendsHstsOnlyOverHttps() throws Exception {
    mvc.perform(get("/").secure(true))
        .andExpect(
            header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
    mvc.perform(get("/")).andExpect(header().doesNotExist("Strict-Transport-Security"));
  }

  /** 화면 경로는 로그인 없이 열리고, 만료된 쿠키가 남아 있어도 막히지 않는다. */
  @Test
  void doesNotRequireAuthenticationForPages() throws Exception {
    mvc.perform(
            get("/pages/market.html")
                .cookie(new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, "expired")))
        .andExpect(status().isNotFound());
  }

  private static Cookie accessCookie(String jtiOrToken) {
    String value = jtiOrToken.startsWith("jti-") ? token(jtiOrToken) : jtiOrToken;
    return new Cookie(SecurityConfig.ACCESS_TOKEN_COOKIE, value);
  }

  private static String token(String jti) {
    return token(jti, Instant.now().plusSeconds(600));
  }

  /** 지정한 jti와 만료 시각으로 서명한 테스트용 토큰을 만든다. */
  private static String token(String jti, Instant expiresAt) {
    JwtEncoder encoder = new SecurityConfig().jwtEncoder(PROPERTIES);
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .id(jti)
            .subject("7")
            .issuedAt(expiresAt.minusSeconds(900))
            .expiresAt(expiresAt)
            .claim("scope", "USER")
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }
}
