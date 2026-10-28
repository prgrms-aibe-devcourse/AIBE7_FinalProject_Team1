package org.example.springtestci.common.security;

import java.time.Duration;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.error.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 기능별 요청 횟수를 Redis로 세어 과도한 반복 요청을 막는다. 첫 요청부터 윈도우가 시작되고, 윈도우가 지나면 횟수가 초기화된다. */
@Component
public class RequestRateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RequestRateLimiter.class);
  private static final String KEY_PREFIX = "rate:";
  private static final long NO_EXPIRY = -1L;

  private final StringRedisTemplate redisTemplate;

  /** 요청 횟수를 저장할 Redis를 주입받는다. */
  public RequestRateLimiter(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /**
   * 요청을 한 번 세고, 한도를 넘으면 다시 시도할 수 있기까지 남은 시간과 함께 예외를 던진다. Redis에 접근할 수 없으면 제한하지 않고 요청을 통과시킨다. 이 제한은
   * 보조 장치라서 Redis 장애가 업로드·채팅 중단으로 번지지 않게 한다.
   */
  public void check(String bucket, String subject, int maxRequests, Duration window) {
    String key = KEY_PREFIX + bucket + ":" + subject;
    Long count;
    Long ttl = null;
    try {
      count = redisTemplate.opsForValue().increment(key);
      if (count == null) {
        return;
      }
      if (count == 1) {
        redisTemplate.expire(key, window);
      }
      if (count > maxRequests) {
        ttl = redisTemplate.getExpire(key);
        // INCR과 EXPIRE 사이에 실패해 만료가 없는 키가 남았다면, 영구 제한이 되지 않도록 윈도우를 다시 건다.
        if (ttl != null && ttl == NO_EXPIRY) {
          redisTemplate.expire(key, window);
        }
      }
    } catch (DataAccessException exception) {
      log.warn("요청 횟수 제한을 확인하지 못해 통과시킵니다. bucket={}", bucket, exception);
      return;
    }
    if (count > maxRequests) {
      throw new RateLimitedException(
          ErrorCode.REQUEST_RATE_LIMITED,
          ttl == null || ttl <= 0 ? window : Duration.ofSeconds(ttl));
    }
  }
}
