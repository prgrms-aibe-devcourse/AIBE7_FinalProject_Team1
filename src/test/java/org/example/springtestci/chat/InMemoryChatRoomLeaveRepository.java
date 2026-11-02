package org.example.springtestci.chat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.example.springtestci.chat.repository.ChatRoomLeaveRepository;

/** Redis 없이 채팅 통합 시험을 돌리기 위한 메모리 시험 대역이다. 운영에서는 Redis 구현을 쓴다. */
public class InMemoryChatRoomLeaveRepository implements ChatRoomLeaveRepository {

  private final Map<String, Instant> left = new ConcurrentHashMap<>();

  @Override
  public boolean markLeft(long roomId, long userId, Instant leftAt) {
    return left.putIfAbsent(roomId + ":" + userId, leftAt) == null;
  }

  @Override
  public Optional<Instant> findLeftAt(long roomId, long userId) {
    return Optional.ofNullable(left.get(roomId + ":" + userId));
  }

  @Override
  public void clearLeft(long roomId, long userId) {
    left.remove(roomId + ":" + userId);
  }
}
