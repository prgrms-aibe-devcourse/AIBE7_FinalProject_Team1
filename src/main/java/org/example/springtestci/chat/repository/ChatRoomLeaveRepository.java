package org.example.springtestci.chat.repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 채팅방 나가기 상태 포트다.
 *
 * <p>DB의 방·메시지는 지우지 않고, 참여자별 "나간 시각"만 별도 저장소에 기록해 본인 목록에서 숨긴다.
 */
public interface ChatRoomLeaveRepository {

  /** 참여자의 나가기 시각을 처음 한 번만 기록한다. 이미 나간 참여자면 false다. */
  boolean markLeft(long roomId, long userId, Instant leftAt);

  /** 참여자가 방을 나간 시각이다. 나가지 않았으면 비어 있다. */
  Optional<Instant> findLeftAt(long roomId, long userId);

  /** 같은 판매글에서 다시 문의를 열면 본인의 나가기 표시를 해제한다. */
  void clearLeft(long roomId, long userId);
}
