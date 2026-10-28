package org.example.springtestci.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.example.springtestci.auth.dto.AuthTokens;
import org.example.springtestci.auth.dto.EmailResendRequest;
import org.example.springtestci.auth.dto.LoginRequest;
import org.example.springtestci.auth.dto.LoginResponse;
import org.example.springtestci.auth.dto.SignupRequest;
import org.example.springtestci.auth.dto.SignupResponse;
import org.example.springtestci.auth.dto.UserResponse;
import org.example.springtestci.auth.service.AuthService;
import org.example.springtestci.auth.service.EmailVerificationService;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.common.security.JwtProperties;
import org.example.springtestci.common.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private static final String REFRESH_COOKIE = "refresh_token";
  private static final String REFRESH_COOKIE_PATH = "/api/auth";

  private final AuthService authService;
  private final EmailVerificationService emailVerificationService;
  private final JwtProperties properties;
  private final Duration unverifiedUserTtl;

  /** 인증 요청을 처리하는 서비스와 토큰 설정, 미인증 계정 유예 시간을 주입받는다. */
  public AuthController(
      AuthService authService,
      EmailVerificationService emailVerificationService,
      JwtProperties properties,
      @Value("${app.auth.unverified-user-ttl:30m}") Duration unverifiedUserTtl) {
    this.authService = authService;
    this.emailVerificationService = emailVerificationService;
    this.properties = properties;
    this.unverifiedUserTtl = unverifiedUserTtl;
  }

  /** 아직 인증하지 못한 계정에 인증 메일을 다시 보낸다. 계정이 있는지는 응답으로 알려 주지 않는다. */
  @PostMapping("/email/resend")
  public ResponseEntity<Void> resendEmailLink(@Valid @RequestBody EmailResendRequest request) {
    emailVerificationService.resend(request.email());
    return ResponseEntity.noContent().build();
  }

  /** 회원가입을 처리하고 인증 메일을 보낸다. */
  @PostMapping("/signup")
  public ResponseEntity<ApiResponse<SignupResponse>> signup(
      @Valid @RequestBody SignupRequest request) {
    UserResponse user = authService.signup(request);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success(SignupResponse.of(user, unverifiedUserTtl.toMinutes())));
  }

  /** 로그인하고 Access·Refresh Token을 HttpOnly 쿠키로 내려준다. */
  @PostMapping("/login")
  public ResponseEntity<ApiResponse<LoginResponse>> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
    return withTokenCookies(authService.login(request, httpRequest.getRemoteAddr()));
  }

  /** Refresh Token 쿠키로 새 토큰 쌍을 발급한다. */
  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<LoginResponse>> refresh(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
    return withTokenCookies(authService.refresh(refreshToken));
  }

  /** 로그아웃한다. Refresh Token 폐기, Access Token 차단, 두 쿠키 만료를 함께 처리한다. */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
      @CookieValue(name = SecurityConfig.ACCESS_TOKEN_COOKIE, required = false) String accessCookie,
      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    authService.logout(refreshToken, bearerOrCookie(authorization, accessCookie));
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
        .header(HttpHeaders.SET_COOKIE, accessCookie("", Duration.ZERO).toString())
        .build();
  }

  /** 로그인한 회원의 정보를 돌려준다. */
  @GetMapping("/me")
  public ApiResponse<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.success(authService.getMe(Long.parseLong(jwt.getSubject())));
  }

  /** Authorization 헤더의 Bearer 토큰을 우선 쓰고, 없으면 Access Token 쿠키 값을 쓴다. */
  private static String bearerOrCookie(String authorization, String accessCookie) {
    if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return authorization.substring(7).trim();
    }
    return accessCookie;
  }

  /** 새 토큰 쌍을 쿠키에 담아 로그인 응답을 만든다. 토큰은 응답 본문에 넣지 않는다. */
  private ResponseEntity<ApiResponse<LoginResponse>> withTokenCookies(AuthTokens tokens) {
    LoginResponse body = new LoginResponse(tokens.expiresIn(), tokens.user());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            refreshCookie(tokens.refreshToken(), properties.refreshTokenTtl()).toString())
        .header(
            HttpHeaders.SET_COOKIE,
            accessCookie(tokens.accessToken(), properties.accessTokenTtl()).toString())
        .body(ApiResponse.success(body));
  }

  /** Refresh Token 쿠키를 만든다. 인증 API 경로에서만 전송된다. */
  private ResponseCookie refreshCookie(String value, Duration maxAge) {
    return httpOnlyCookie(REFRESH_COOKIE, value, REFRESH_COOKIE_PATH, maxAge);
  }

  /** Access Token 쿠키를 만든다. 모든 경로로 전송된다. */
  private ResponseCookie accessCookie(String value, Duration maxAge) {
    return httpOnlyCookie(SecurityConfig.ACCESS_TOKEN_COOKIE, value, "/", maxAge);
  }

  /** HttpOnly·SameSite=Strict를 적용한 인증 쿠키를 만든다. */
  private ResponseCookie httpOnlyCookie(String name, String value, String path, Duration maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(properties.refreshCookieSecure())
        .sameSite("Strict")
        .path(path)
        .maxAge(maxAge)
        .build();
  }
}
