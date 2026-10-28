package org.example.springtestci.common.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RequestRateLimiterTest {

  private static final String KEY = "rate:chat-send:user:1";
  private static final Duration WINDOW = Duration.ofMinutes(1);

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private RequestRateLimiter limiter;

  /** 테스트마다 가짜 Redis로 제한기를 만든다. */
  @BeforeEach
  void setUp() {
    limiter = new RequestRateLimiter(redisTemplate);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  /** 첫 요청은 허용하고 윈도우를 시작한다. */
  @Test
  void firstRequestStartsTheWindow() {
    when(valueOperations.increment(KEY)).thenReturn(1L);

    assertDoesNotThrow(() -> limiter.check("chat-send", "user:1", 30, WINDOW));

    verify(redisTemplate).expire(KEY, WINDOW);
  }

  /** 한도까지는 허용하고 윈도우를 다시 시작하지 않는다. */
  @Test
  void allowsRequestsUpToTheLimit() {
    when(valueOperations.increment(KEY)).thenReturn(30L);

    assertDoesNotThrow(() -> limiter.check("chat-send", "user:1", 30, WINDOW));

    verify(redisTemplate, never()).expire(KEY, WINDOW);
  }

  /** 한도를 넘으면 남은 시간과 함께 거부한다. */
  @Test
  void rejectsRequestsOverTheLimitWithRemainingTime() {
    when(valueOperations.increment(KEY)).thenReturn(31L);
    when(redisTemplate.getExpire(KEY)).thenReturn(42L);

    RateLimitedException exception =
        assertThrows(
            RateLimitedException.class, () -> limiter.check("chat-send", "user:1", 30, WINDOW));

    assertEquals(ErrorCode.REQUEST_RATE_LIMITED, exception.errorCode());
    assertEquals(Duration.ofSeconds(42), exception.retryAfter());
  }

  /** 남은 시간을 알 수 없으면 윈도우 전체를 안내한다. */
  @Test
  void fallsBackToTheWholeWindowWhenTtlIsUnknown() {
    when(valueOperations.increment(KEY)).thenReturn(31L);
    when(redisTemplate.getExpire(KEY)).thenReturn(-1L);

    RateLimitedException exception =
        assertThrows(
            RateLimitedException.class, () -> limiter.check("chat-send", "user:1", 30, WINDOW));

    assertEquals(WINDOW, exception.retryAfter());
  }

  /** 만료 없이 남은 키는 영구 제한이 되지 않도록 윈도우를 다시 건다. */
  @Test
  void restoresTheWindowWhenTheKeyHasNoExpiry() {
    when(valueOperations.increment(KEY)).thenReturn(31L);
    when(redisTemplate.getExpire(KEY)).thenReturn(-1L);

    assertThrows(
        RateLimitedException.class, () -> limiter.check("chat-send", "user:1", 30, WINDOW));

    verify(redisTemplate).expire(KEY, WINDOW);
  }

  /** Redis에 접근할 수 없으면 요청을 막지 않고 통과시킨다. */
  @Test
  void letsRequestsThroughWhenRedisIsUnavailable() {
    when(valueOperations.increment(KEY)).thenThrow(new RedisConnectionFailureException("down"));

    assertDoesNotThrow(() -> limiter.check("chat-send", "user:1", 30, WINDOW));
  }
}
