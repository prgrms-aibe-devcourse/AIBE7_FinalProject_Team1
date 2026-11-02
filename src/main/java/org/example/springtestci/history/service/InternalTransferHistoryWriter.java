package org.example.springtestci.history.service;

import java.time.Instant;

/**
 * 소유권 이전 완료 트랜잭션이 시스템 TRANSFER 이력을 한 번 기록하기 위한 내부 포트다.
 *
 * <p>호출자는 제품 행을 잠근 상태에서 소유자를 수신자로 변경한 뒤 호출한다. 구현은 별도 트랜잭션을 열지 않으며 이전 요청 ID의 멱등성을 보장한다.
 */
public interface InternalTransferHistoryWriter {

  void recordCompletedTransfer(Command command);

  /** 완료 재시도 검증용으로, 같은 이전 요청의 시스템 이력이 해당 제품에 존재하는지 확인한다. */
  boolean hasCompletedTransfer(long productId, long transferRequestId);

  record Command(
      long productId, long transferRequestId, long fromUserId, long toUserId, Instant completedAt) {
    public Command {
      if (productId <= 0
          || transferRequestId <= 0
          || fromUserId <= 0
          || toUserId <= 0
          || fromUserId == toUserId
          || completedAt == null) {
        throw new IllegalArgumentException("Invalid completed transfer history command");
      }
    }
  }
}
