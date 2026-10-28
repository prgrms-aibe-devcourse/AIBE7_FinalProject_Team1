package org.example.springtestci.auth.service;

import java.time.Duration;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.error.RateLimitedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 횟수를 Redis로 세어 무차별 대입을 막는다. 이메일별 5회, IP별 20회까지 실패할 수 있고, 첫 실패 후 15분이 지나면 해제된다. 제한 중에는 비밀번호가
 * 맞아도 로그인할 수 없다.
 */
@Component
public class LoginRateLimiter {

  static final int MAX_EMAIL_FAILURES = 5;
  static final int MAX_IP_FAILURES = 20;
  static final Duration WINDOW = Duration.ofMinutes(15);

  private static final String EMAIL_KEY = "auth:login:fail:email:";
  private static final String IP_KEY = "auth:login:fail:ip:";
  private static final long NO_EXPIRY = -1L;

  private final StringRedisTemplate redisTemplate;

  /** 실패 횟수를 저장할 Redis를 주입받는다. */
  public LoginRateLimiter(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /** 제한 중이면 다시 시도할 수 있기까지 남은 시간과 함께 예외를 던진다. */
  public void checkAllowed(String normalizedEmail, String clientIp) {
    checkKey(EMAIL_KEY + normalizedEmail, MAX_EMAIL_FAILURES);
    checkKey(IP_KEY + clientIp, MAX_IP_FAILURES);
  }

  /** 로그인 실패를 이메일과 IP 기준으로 한 번씩 센다. */
  public void recordFailure(String normalizedEmail, String clientIp) {
    increment(EMAIL_KEY + normalizedEmail);
    increment(IP_KEY + clientIp);
  }

  /** 로그인에 성공하면 해당 이메일의 실패 횟수를 지운다. IP별 횟수는 다른 계정 시도와 합산되므로 남긴다. */
  public void reset(String normalizedEmail) {
    redisTemplate.delete(EMAIL_KEY + normalizedEmail);
  }

  /** 실패 횟수가 한도에 이르렀으면 남은 제한 시간과 함께 예외를 던진다. */
  private void checkKey(String key, int max) {
    String count = redisTemplate.opsForValue().get(key);
    if (count == null || Long.parseLong(count) < max) {
      return;
    }
    Long ttl = redisTemplate.getExpire(key);
    // INCR과 EXPIRE 사이에 실패해 만료가 없는 키가 남았다면, 영구 잠금이 되지 않도록 윈도우를 다시 건다.
    if (ttl != null && ttl == NO_EXPIRY) {
      redisTemplate.expire(key, WINDOW);
    }
    throw new RateLimitedException(
        ErrorCode.LOGIN_RATE_LIMITED, ttl == null || ttl <= 0 ? WINDOW : Duration.ofSeconds(ttl));
  }

  /** 횟수를 1 올리고, 첫 실패면 제한 시간(윈도우)을 시작한다. */
  private void increment(String key) {
    Long count = redisTemplate.opsForValue().increment(key);
    if (count != null && count == 1) {
      redisTemplate.expire(key, WINDOW);
    }
  }
}
