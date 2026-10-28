package org.example.springtestci.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
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
    redisTemplate
        .opsForValue()
        .set(key(token), String.valueOf(userId), properties.refreshTokenTtl());
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

  /** 로그아웃 때 Refresh Token을 폐기한다. */
  public void revoke(String token) {
    redisTemplate.delete(key(token));
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
