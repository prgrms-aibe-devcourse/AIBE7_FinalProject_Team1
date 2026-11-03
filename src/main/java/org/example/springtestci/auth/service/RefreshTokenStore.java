package org.example.springtestci.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.example.springtestci.common.security.JwtProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Refresh Token을 Redis에서 관리한다. 원문 토큰은 저장하지 않고 SHA-256 해시를 키로 쓴다. */
@Component
public class RefreshTokenStore {

  private static final String KEY_PREFIX = "auth:refresh:";

  /** 회원이 지금 로그인 중임을 나타내는 표시. 토큰을 발급·갱신할 때마다 연장하고, 로그아웃하면 지운다. */
  private static final String SESSION_PREFIX = "auth:session:";

  /** 회원의 현재 Refresh Token 키. 새 로그인이 시작되면 이전 기기의 토큰을 바로 폐기하는 데 쓴다. */
  private static final String CURRENT_PREFIX = "auth:session-token:";

  /**
   * 마지막 토큰 발급·갱신 뒤 이 시간 동안은 "로그인 중"으로 보고 다른 곳의 로그인을 막는다. Access Token(15분)이 만료될 때마다 갱신되므로 사용 중인 세션은
   * 계속 유지되고, 로그아웃 없이 창을 닫은 경우에도 이 시간이 지나면 다시 로그인할 수 있다.
   */
  static final Duration SESSION_LEASE = Duration.ofMinutes(30);

  private static final int TOKEN_BYTES = 32;

  private final StringRedisTemplate redisTemplate;
  private final JwtProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  /** Redis와 토큰 만료 설정을 주입받는다. */
  public RefreshTokenStore(StringRedisTemplate redisTemplate, JwtProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
  }

  /** 새 Refresh Token을 만들어 해시 키로 Redis에 저장하고, 원문 토큰을 돌려준다. */
  public String issue(long userId) {
    byte[] bytes = new byte[TOKEN_BYTES];
    secureRandom.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    String tokenKey = key(token);
    redisTemplate.opsForValue().set(tokenKey, String.valueOf(userId), properties.refreshTokenTtl());
    // 현재 토큰을 기억하고 로그인 중 표시를 연장한다. 토큰 갱신(rotation)도 이 경로를 지난다.
    redisTemplate
        .opsForValue()
        .set(CURRENT_PREFIX + userId, tokenKey, properties.refreshTokenTtl());
    redisTemplate.opsForValue().set(SESSION_PREFIX + userId, "1", SESSION_LEASE);
    return token;
  }

  /** 토큰을 한 번만 쓸 수 있게 조회와 동시에 삭제한다(재사용 방지). */
  public Optional<Long> consume(String token) {
    String userId = redisTemplate.opsForValue().getAndDelete(key(token));
    if (userId == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Long.parseLong(userId));
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  /**
   * 새 로그인을 시작한다. 이미 로그인 중이면 {@code false}를 돌려주고 아무것도 바꾸지 않는다. 표시가 없으면(처음이거나 오래 사용하지 않아 만료) 원자적으로
   * 표시를 만들어, 동시에 로그인해도 한 곳만 성공한다. 이때 이전 기기에 남은 Refresh Token은 함께 폐기한다.
   */
  public boolean startSession(long userId) {
    Boolean created =
        redisTemplate.opsForValue().setIfAbsent(SESSION_PREFIX + userId, "1", SESSION_LEASE);
    if (!Boolean.TRUE.equals(created)) {
      return false;
    }
    String previous = redisTemplate.opsForValue().getAndDelete(CURRENT_PREFIX + userId);
    if (previous != null) {
      redisTemplate.delete(previous);
    }
    return true;
  }

  /**
   * 이미 로그인 중인 회원이 같은 브라우저에서 다시 로그인하는 경우를 허용한다. 요청이 가져온 Refresh Token이 그 회원의 현재 토큰일 때만 {@code true}를
   * 돌려주고, 그 토큰을 폐기한 뒤 로그인 중 표시를 연장한다. 토큰 삭제 성공 여부로 판정하므로 같은 토큰으로 동시에 요청해도 한 번만 통과한다. 이어서 발급하는 새 토큰이
   * 현재 토큰이 된다.
   */
  public boolean resumeSession(long userId, String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return false;
    }
    String tokenKey = key(refreshToken);
    String current = redisTemplate.opsForValue().get(CURRENT_PREFIX + userId);
    if (!tokenKey.equals(current)) {
      return false;
    }
    if (!Boolean.TRUE.equals(redisTemplate.delete(tokenKey))) {
      return false;
    }
    redisTemplate.opsForValue().set(SESSION_PREFIX + userId, "1", SESSION_LEASE);
    return true;
  }

  /** 회원의 로그인 중 표시와 현재 토큰을 지운다. 로그아웃하거나 로그인 시작이 실패했을 때 쓴다. */
  public void endSession(long userId) {
    String current = redisTemplate.opsForValue().getAndDelete(CURRENT_PREFIX + userId);
    if (current != null) {
      redisTemplate.delete(current);
    }
    redisTemplate.delete(SESSION_PREFIX + userId);
  }

  /** 로그아웃 때 Refresh Token을 폐기하고, 그 회원의 로그인 중 표시도 함께 지운다. */
  public void revoke(String token) {
    String userId = redisTemplate.opsForValue().getAndDelete(key(token));
    if (userId == null) {
      return;
    }
    try {
      endSession(Long.parseLong(userId));
    } catch (NumberFormatException exception) {
      // 잘못된 값이면 토큰만 지운 채 둔다.
    }
  }

  /** 토큰 원문 대신 SHA-256 해시로 Redis 키를 만든다. */
  private static String key(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return KEY_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 not available", exception);
    }
  }
}
