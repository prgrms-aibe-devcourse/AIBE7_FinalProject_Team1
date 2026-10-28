package org.example.springtestci.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Optional;
import org.example.springtestci.auth.dto.AuthTokens;
import org.example.springtestci.auth.dto.LoginRequest;
import org.example.springtestci.auth.dto.SignupRequest;
import org.example.springtestci.auth.dto.UserResponse;
import org.example.springtestci.auth.entity.User;
import org.example.springtestci.auth.entity.UserRole;
import org.example.springtestci.auth.repository.UserRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.error.RateLimitedException;
import org.example.springtestci.common.security.AccessTokenBlocklist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  private static final String IP = "127.0.0.1";

  @Mock private UserRepository userRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private JwtTokenProvider jwtTokenProvider;
  @Mock private RefreshTokenStore refreshTokenStore;
  @Mock private EmailVerificationService emailVerificationService;
  @Mock private LoginRateLimiter loginRateLimiter;
  @Mock private JwtDecoder jwtDecoder;
  @Mock private AccessTokenBlocklist accessTokenBlocklist;

  private AuthService authService;

  /** 테스트마다 가짜 구성 요소로 AuthService를 만든다. */
  @BeforeEach
  void setUp() {
    when(passwordEncoder.encode("dummy-password-for-timing")).thenReturn("dummy-hash");
    authService =
        new AuthService(
            userRepository,
            passwordEncoder,
            jwtTokenProvider,
            refreshTokenStore,
            emailVerificationService,
            loginRateLimiter,
            jwtDecoder,
            accessTokenBlocklist);
  }

  /** 가입 시 이메일을 정규화하고 비밀번호는 해시만 저장한다. */
  @Test
  void signupNormalizesEmailAndStoresOnlyTheHashedPassword() {
    when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
    when(userRepository.existsByNicknameIgnoreCase("기록왕")).thenReturn(false);
    when(passwordEncoder.encode("Passw0rd!")).thenReturn("hashed");
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));

    UserResponse response =
        authService.signup(new SignupRequest("  User@Example.COM ", "Passw0rd!", " 기록왕 "));

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(userRepository).saveAndFlush(saved.capture());
    assertEquals("user@example.com", saved.getValue().getEmail());
    assertEquals("hashed", saved.getValue().getPasswordHash());
    assertEquals("기록왕", saved.getValue().getNickname());
    assertEquals(UserRole.USER, saved.getValue().getRole());
    assertEquals("user@example.com", response.email());
    verify(emailVerificationService).sendLink("user@example.com");
  }

  /** 이미 가입된 이메일로는 가입할 수 없다. */
  @Test
  void signupRejectsADuplicateEmail() {
    when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.signup(new SignupRequest("user@example.com", "Passw0rd!", "기록왕")));

    assertEquals(ErrorCode.DUPLICATE_EMAIL, exception.errorCode());
    verify(userRepository, never()).saveAndFlush(any());
  }

  /** 이미 쓰는 닉네임으로는 가입할 수 없다. */
  @Test
  void signupRejectsADuplicateNickname() {
    when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
    when(userRepository.existsByNicknameIgnoreCase("기록왕")).thenReturn(true);

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.signup(new SignupRequest("user@example.com", "Passw0rd!", "기록왕")));

    assertEquals(ErrorCode.DUPLICATE_NICKNAME, exception.errorCode());
  }

  /** 인증 메일을 보내지 못하면 가입 전체가 실패한다. */
  @Test
  void signupFailsWhenTheVerificationMailCannotBeSent() {
    when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
    when(userRepository.existsByNicknameIgnoreCase("기록왕")).thenReturn(false);
    when(passwordEncoder.encode("Passw0rd!")).thenReturn("hashed");
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
    doThrow(new CommonException(ErrorCode.EMAIL_SEND_FAILED))
        .when(emailVerificationService)
        .sendLink("user@example.com");

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.signup(new SignupRequest("user@example.com", "Passw0rd!", "기록왕")));

    assertEquals(ErrorCode.EMAIL_SEND_FAILED, exception.errorCode());
    verify(userRepository).delete(any(User.class));
  }

  /** 메일 발송 실패 후 계정 삭제까지 실패해도 원래 오류를 그대로 돌려준다. */
  @Test
  void signupKeepsTheMailErrorWhenTheCleanupAlsoFails() {
    when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
    when(userRepository.existsByNicknameIgnoreCase("기록왕")).thenReturn(false);
    when(passwordEncoder.encode("Passw0rd!")).thenReturn("hashed");
    when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
    doThrow(new CommonException(ErrorCode.EMAIL_SEND_FAILED))
        .when(emailVerificationService)
        .sendLink("user@example.com");
    doThrow(new IllegalStateException("db down")).when(userRepository).delete(any(User.class));

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.signup(new SignupRequest("user@example.com", "Passw0rd!", "기록왕")));

    assertEquals(ErrorCode.EMAIL_SEND_FAILED, exception.errorCode());
  }

  /** 동시 가입으로 DB 유니크 제약에 걸리면 중복 오류로 바꾼다. */
  @Test
  void signupMapsAConcurrentUniqueViolationToADuplicateError() {
    when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
    when(userRepository.existsByNicknameIgnoreCase("기록왕")).thenReturn(false);
    when(passwordEncoder.encode("Passw0rd!")).thenReturn("hashed");
    when(userRepository.saveAndFlush(any(User.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "conflict",
                new RuntimeException("duplicate key value violates uk_users_nickname")));

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.signup(new SignupRequest("user@example.com", "Passw0rd!", "기록왕")));

    assertEquals(ErrorCode.DUPLICATE_NICKNAME, exception.errorCode());
  }

  /** 로그인에 성공하면 Access·Refresh Token을 발급한다. */
  @Test
  void loginIssuesAccessAndRefreshTokens() throws Exception {
    User user = user(7L, "user@example.com", "hashed");
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("Passw0rd!", "hashed")).thenReturn(true);
    when(jwtTokenProvider.createAccessToken(user)).thenReturn("access");
    when(jwtTokenProvider.accessTokenExpiresInSeconds()).thenReturn(900L);
    when(refreshTokenStore.issue(7L)).thenReturn("refresh");

    AuthTokens tokens = authService.login(new LoginRequest(" User@Example.com", "Passw0rd!"), IP);

    assertEquals("access", tokens.accessToken());
    assertEquals("refresh", tokens.refreshToken());
    assertEquals(900L, tokens.expiresIn());
    assertEquals(7L, tokens.user().id());
  }

  /** 틀린 비밀번호는 거부하고 토큰을 발급하지 않는다. */
  @Test
  void loginRejectsAWrongPassword() throws Exception {
    User user = user(7L, "user@example.com", "hashed");
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.login(new LoginRequest("user@example.com", "wrong"), IP));

    assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.errorCode());
    verifyNoInteractions(refreshTokenStore);
  }

  /** 인증 링크를 누르기 전에는 로그인할 수 없다. */
  @Test
  void loginRejectsAnUnverifiedEmailUntilTheLinkIsClicked() throws Exception {
    User user = unverifiedUser(7L, "user@example.com", "hashed");
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("Passw0rd!", "hashed")).thenReturn(true);
    when(emailVerificationService.confirmIfVerified("user@example.com")).thenReturn(false);

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.login(new LoginRequest("user@example.com", "Passw0rd!"), IP));

    assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, exception.errorCode());
    assertFalse(user.isEmailVerified());
    verifyNoInteractions(refreshTokenStore);
  }

  /** 링크를 누른 뒤 로그인하면 인증 완료로 바꾸고 토큰을 발급한다. */
  @Test
  void loginMarksTheUserVerifiedOnceTheLinkWasClicked() throws Exception {
    User user = unverifiedUser(7L, "user@example.com", "hashed");
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("Passw0rd!", "hashed")).thenReturn(true);
    when(emailVerificationService.confirmIfVerified("user@example.com")).thenReturn(true);
    when(jwtTokenProvider.createAccessToken(user)).thenReturn("access");
    when(jwtTokenProvider.accessTokenExpiresInSeconds()).thenReturn(900L);
    when(refreshTokenStore.issue(7L)).thenReturn("refresh");

    AuthTokens tokens = authService.login(new LoginRequest("user@example.com", "Passw0rd!"), IP);

    assertTrue(user.isEmailVerified());
    verify(userRepository).save(user);
    assertEquals("access", tokens.accessToken());
  }

  /** 비밀번호가 틀리면 미인증 여부를 알리지 않는다. */
  @Test
  void loginWithAWrongPasswordNeverRevealsThatTheEmailIsUnverified() throws Exception {
    User user = unverifiedUser(7L, "user@example.com", "hashed");
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.login(new LoginRequest("user@example.com", "wrong"), IP));

    assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.errorCode());
    verifyNoInteractions(emailVerificationService);
  }

  /** 없는 이메일도 같은 오류로 실패하고 해시 비교 비용을 치른다. */
  @Test
  void loginForAnUnknownEmailFailsTheSameWayAndStillComparesAHash() {
    when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.matches("Passw0rd!", "dummy-hash")).thenReturn(false);

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> authService.login(new LoginRequest("nobody@example.com", "Passw0rd!"), IP));

    assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.errorCode());
    verify(passwordEncoder).matches("Passw0rd!", "dummy-hash");
  }

  /** 토큰을 갱신하면 새 토큰 쌍을 발급한다. */
  @Test
  void refreshRotatesTheTokenPair() throws Exception {
    User user = user(7L, "user@example.com", "hashed");
    when(refreshTokenStore.consume("old")).thenReturn(Optional.of(7L));
    when(userRepository.findById(7L)).thenReturn(Optional.of(user));
    when(jwtTokenProvider.createAccessToken(user)).thenReturn("access2");
    when(jwtTokenProvider.accessTokenExpiresInSeconds()).thenReturn(900L);
    when(refreshTokenStore.issue(7L)).thenReturn("new");

    AuthTokens tokens = authService.refresh("old");

    assertEquals("access2", tokens.accessToken());
    assertEquals("new", tokens.refreshToken());
  }

  /** 모르는 토큰이나 이미 쓴 토큰은 갱신할 수 없다. */
  @Test
  void refreshRejectsAnUnknownOrReusedToken() {
    when(refreshTokenStore.consume("stale")).thenReturn(Optional.empty());

    CommonException exception =
        assertThrows(CommonException.class, () -> authService.refresh("stale"));

    assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, exception.errorCode());
  }

  /** 토큰이 없으면 갱신을 거부한다. */
  @Test
  void refreshRejectsAMissingToken() {
    CommonException exception =
        assertThrows(CommonException.class, () -> authService.refresh(null));

    assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, exception.errorCode());
    verifyNoInteractions(refreshTokenStore);
  }

  /** 로그인 제한 중에는 비밀번호를 비교하지 않고 거부한다. */
  @Test
  void loginIsRejectedWithoutComparingThePasswordWhileRateLimited() {
    doThrow(new RateLimitedException(ErrorCode.LOGIN_RATE_LIMITED, Duration.ofMinutes(10)))
        .when(loginRateLimiter)
        .checkAllowed("user@example.com", IP);

    RateLimitedException exception =
        assertThrows(
            RateLimitedException.class,
            () -> authService.login(new LoginRequest("user@example.com", "Passw0rd!"), IP));

    assertEquals(ErrorCode.LOGIN_RATE_LIMITED, exception.errorCode());
    verifyNoInteractions(userRepository, refreshTokenStore);
    verify(passwordEncoder, never()).matches(any(), any());
  }

  /** 틀리면 실패를 기록하고, 성공하면 실패 횟수를 지운다. */
  @Test
  void loginRecordsAFailureForAWrongPasswordAndClearsItOnSuccess() throws Exception {
    User user = user(7L, "user@example.com", "hashed");
    when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
    when(passwordEncoder.matches("Passw0rd!", "hashed")).thenReturn(true);
    when(jwtTokenProvider.createAccessToken(user)).thenReturn("access");
    when(refreshTokenStore.issue(7L)).thenReturn("refresh");

    assertThrows(
        CommonException.class,
        () -> authService.login(new LoginRequest("user@example.com", "wrong"), IP));
    verify(loginRateLimiter).recordFailure("user@example.com", IP);
    verify(loginRateLimiter, never()).reset("user@example.com");

    authService.login(new LoginRequest("user@example.com", "Passw0rd!"), IP);
    verify(loginRateLimiter).reset("user@example.com");
  }

  /** 로그아웃하면 Refresh Token을 폐기하고 Access Token을 차단한다. */
  @Test
  void logoutRevokesTheRefreshTokenAndBlocksTheAccessToken() {
    Jwt jwt = Jwt.withTokenValue("access").header("alg", "HS256").subject("7").build();
    when(jwtDecoder.decode("access")).thenReturn(jwt);

    authService.logout("token", "access");

    verify(refreshTokenStore).revoke("token");
    verify(accessTokenBlocklist).block(jwt);
  }

  /** 이미 유효하지 않은 Access Token은 차단하지 않고 넘어간다. */
  @Test
  void logoutIgnoresAnAccessTokenThatIsAlreadyInvalid() {
    when(jwtDecoder.decode("expired")).thenThrow(new JwtException("expired"));

    authService.logout("token", "expired");

    verify(refreshTokenStore).revoke("token");
    verifyNoInteractions(accessTokenBlocklist);
  }

  /** 토큰이 없으면 아무 일도 하지 않는다. */
  @Test
  void logoutWithoutATokenDoesNothing() {
    authService.logout(null, null);

    verifyNoInteractions(refreshTokenStore, jwtDecoder, accessTokenBlocklist);
  }

  /** 인증을 마친 테스트용 회원을 만든다. */
  private static User user(long id, String email, String passwordHash) throws Exception {
    User user = unverifiedUser(id, email, passwordHash);
    user.markEmailVerified();
    return user;
  }

  /** 인증 전인 테스트용 회원을 만든다. */
  private static User unverifiedUser(long id, String email, String passwordHash) throws Exception {
    User user = new User(email, passwordHash, "기록왕", UserRole.USER);
    Field idField = User.class.getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(user, id);
    return user;
  }
}
