package org.example.springtestci.chat.repository.redis;

import java.time.Instant;
import java.util.Optional;
import org.example.springtestci.chat.repository.ChatRoomLeaveRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * 나가기 상태의 운영 Redis 구현이다. 값은 나간 시각(epoch millis)이며 만료 없이 보관한다.
 *
 * <p>DB 스키마를 바꾸지 않기 위해 Redis를 쓴다. Redis는 AOF 영속화를 사용하지만, 저장소가 초기화되면 숨긴 방이 다시 보일 수 있다.
 */
@Repository
class RedisChatRoomLeaveRepository implements ChatRoomLeaveRepository {

  private static final String KEY_PREFIX = "chat:room-left:";

  private final StringRedisTemplate redisTemplate;

  RedisChatRoomLeaveRepository(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  /** 이미 키가 있으면 덮어쓰지 않아 나간 시각과 안내가 중복되지 않는다. */
  @Override
  public boolean markLeft(long roomId, long userId, Instant leftAt) {
    return Boolean.TRUE.equals(
        redisTemplate
            .opsForValue()
            .setIfAbsent(key(roomId, userId), String.valueOf(leftAt.toEpochMilli())));
  }

  @Override
  public Optional<Instant> findLeftAt(long roomId, long userId) {
    String value = redisTemplate.opsForValue().get(key(roomId, userId));
    if (value == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.ofEpochMilli(Long.parseLong(value)));
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  @Override
  public void clearLeft(long roomId, long userId) {
    redisTemplate.delete(key(roomId, userId));
  }

  private String key(long roomId, long userId) {
    return KEY_PREFIX + roomId + ":" + userId;
  }
}
