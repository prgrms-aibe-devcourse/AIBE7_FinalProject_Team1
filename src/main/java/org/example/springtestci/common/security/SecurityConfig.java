package org.example.springtestci.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

  public static final String ACCESS_TOKEN_COOKIE = "access_token";

  /** CSRF 검증, 쿠키 인증, 접근 규칙을 설정한다. */
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(
            csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    // JS가 쿠키에서 읽은 값을 그대로 헤더로 보내므로 마스킹하지 않는 핸들러를 쓴다.
                    // Authorization 헤더가 있는 요청(API 클라이언트, Swagger)은 Spring이 기본으로 CSRF 검사에서 뺀다.
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        // 쿠키 요청이 CSRF 검사를 받은 뒤에 쿠키를 Authorization 헤더로 바꿔 넘긴다.
        .addFilterBefore(new CookieBearerTokenFilter(), BearerTokenAuthenticationFilter.class)
        // CSRF 검사에 실패하면 인증 문제(401)가 아니라 요청 거부(403)로 응답한다.
        .exceptionHandling(handling -> handling.accessDeniedHandler(new AccessDeniedHandlerImpl()))
        .headers(
            headers ->
                headers
                    // HTTPS 요청 응답에만 붙는다. 로컬 HTTP에서는 무시된다.
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                    .addHeaderWriter(
                        new StaticHeadersWriter(
                            "Permissions-Policy", "geolocation=(), microphone=()")))
        .cors(Customizer.withDefaults())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/",
                        "/index.html",
                        "/image.png",
                        "/favicon.svg",
                        "/email-verified.html",
                        "/css/**",
                        "/js/**",
                        "/pages/**",
                        "/images/**",
                        "/local-files",
                        "/error",
                        "/ws-stomp",
                        "/ws-stomp/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    // 화면 이름 경로와 없는 경로: API가 아닌 GET은 인증 대상이 아니다. 없는 경로가 401이 아니라 404로 응답한다.
                    .requestMatchers(
                        request ->
                            "GET".equals(request.getMethod())
                                && !request.getRequestURI().startsWith("/api/"))
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/listings",
                        "/api/v1/listings/*/thumbnail",
                        "/api/v1/ownership/transfers/verify",
                        "/api/v1/products/*/events")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/auth/email/resend",
                        "/api/auth/signup",
                        "/api/auth/login",
                        "/api/auth/refresh",
                        "/api/auth/logout",
                        "/api/v1/ai/ask",
                        "/api/v1/ai/estimate-price")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
  }

  /** JWT 디코더를 만든다. 로그아웃으로 차단된 토큰은 거부한다. */
  @Bean
  JwtDecoder jwtDecoder(JwtProperties properties, AccessTokenBlocklist blocklist) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(secretKey(properties))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    // 로그아웃으로 차단된 토큰은 서명과 만료가 유효해도 거부한다(401).
    OAuth2TokenValidator<Jwt> notBlocked =
        jwt ->
            blocklist.isBlocked(jwt.getId())
                ? OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "로그아웃된 토큰입니다.", null))
                : OAuth2TokenValidatorResult.success();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), notBlocked));
    return decoder;
  }

  /** JWT 서명용 인코더를 만든다. */
  @Bean
  JwtEncoder jwtEncoder(JwtProperties properties) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
  }

  /** 설정의 비밀 값으로 HS256 서명 키를 만든다. */
  private static SecretKey secretKey(JwtProperties properties) {
    return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  /** 비밀번호를 단방향 해시(BCrypt)로 저장하기 위한 인코더를 만든다. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
