package org.example.springtestci.auth.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.error.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

  private static final String EMAIL_KEY = "auth:login:fail:email:user@example.com";
  private static final String IP_KEY = "auth:login:fail:ip:10.0.0.1";

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private LoginRateLimiter limiter;

  /** 테스트마다 가짜 Redis로 제한기를 만든다. */
  @BeforeEach
  void setUp() {
    limiter = new LoginRateLimiter(redisTemplate);
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  /** 실패 횟수가 한도 아래면 허용한다. */
  @Test
  void allowsLoginsBelowTheFailureLimit() {
    when(valueOperations.get(EMAIL_KEY)).thenReturn("4");
    when(valueOperations.get(IP_KEY)).thenReturn("19");

    assertDoesNotThrow(() -> limiter.checkAllowed("user@example.com", "10.0.0.1"));
  }

  /** 이메일이 너무 자주 실패하면 거부하고 다시 시도할 시점을 알린다. */
  @Test
  void rejectsAnEmailThatFailedTooManyTimesAndTellsWhenToRetry() {
    when(valueOperations.get(EMAIL_KEY)).thenReturn("5");
    when(redisTemplate.getExpire(EMAIL_KEY)).thenReturn(600L);

    RateLimitedException exception =
        assertThrows(
            RateLimitedException.class, () -> limiter.checkAllowed("user@example.com", "10.0.0.1"));

    assertEquals(ErrorCode.LOGIN_RATE_LIMITED, exception.errorCode());
    assertEquals(Duration.ofSeconds(600), exception.retryAfter());
  }

  /** 여러 계정에 걸쳐 너무 자주 실패한 IP를 거부한다. */
  @Test
  void rejectsAnIpThatFailedTooManyTimesAcrossAccounts() {
    when(valueOperations.get(EMAIL_KEY)).thenReturn(null);
    when(valueOperations.get(IP_KEY)).thenReturn("20");
    when(redisTemplate.getExpire(IP_KEY)).thenReturn(null);

    RateLimitedException exception =
        assertThrows(
            RateLimitedException.class, () -> limiter.checkAllowed("user@example.com", "10.0.0.1"));

    assertEquals(Duration.ofMinutes(15), exception.retryAfter());
  }

  /** 만료 없이 남은 잠금 키는 영구 잠금이 되지 않도록 제한 시간을 다시 건다. */
  @Test
  void restoresTheWindowWhenALockedKeyHasNoExpiry() {
    when(valueOperations.get(EMAIL_KEY)).thenReturn("5");
    when(redisTemplate.getExpire(EMAIL_KEY)).thenReturn(-1L);

    RateLimitedException exception =
        assertThrows(
            RateLimitedException.class, () -> limiter.checkAllowed("user@example.com", "10.0.0.1"));

    assertEquals(Duration.ofMinutes(15), exception.retryAfter());
    verify(redisTemplate).expire(EMAIL_KEY, Duration.ofMinutes(15));
  }

  /** 제한 시간은 첫 실패에서만 시작한다. */
  @Test
  void startsTheWindowOnTheFirstFailureOnly() {
    when(valueOperations.increment(EMAIL_KEY)).thenReturn(1L);
    when(valueOperations.increment(IP_KEY)).thenReturn(3L);

    limiter.recordFailure("user@example.com", "10.0.0.1");

    verify(redisTemplate).expire(EMAIL_KEY, Duration.ofMinutes(15));
    verify(redisTemplate, never()).expire(IP_KEY, Duration.ofMinutes(15));
  }

  /** 초기화는 이메일 횟수만 지운다. */
  @Test
  void resetClearsOnlyTheEmailCounter() {
    limiter.reset("user@example.com");

    verify(redisTemplate).delete(EMAIL_KEY);
    verify(redisTemplate, never()).delete(IP_KEY);
  }
}
