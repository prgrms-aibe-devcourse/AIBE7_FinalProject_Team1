package org.example.springtestci.history.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.history.repository.HistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정식 소유권 이전이 완료된 뒤 개인정보가 없는 시스템 이력을 누적한다.
 *
 * <p>제품 소유자가 수신자로 변경됐는지 확인한 후 PRIVATE TRANSFER 이력을 저장하고 누적 이력을 비공개로 전환한다. 제품 사실 버전은 소유권 모듈이 소유자 변경과
 * 함께 증가시킨다. 같은 이전 요청의 재호출은 기존 이력을 반환하는 효과로 종료하며, 다른 제품과 충돌하면 전체 이전 트랜잭션을 실패시킨다.
 */
@Service
public class DefaultInternalTransferHistoryWriter implements InternalTransferHistoryWriter {

  private final HistoryRepository historyRepository;
  private final Clock clock;

  public DefaultInternalTransferHistoryWriter(HistoryRepository historyRepository, Clock clock) {
    this.historyRepository = historyRepository;
    this.clock = clock;
  }

  /** 소유권 서비스가 시작한 트랜잭션에 반드시 참여해 소유자 변경과 이력을 원자적으로 저장한다. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordCompletedTransfer(Command command) {
    var previous = historyRepository.findTransferEvent(command.transferRequestId());
    if (previous.isPresent()) {
      if (previous.get().productId() != command.productId()) conflict();
      return;
    }
    if (!historyRepository.isCurrentOwner(command.productId(), command.toUserId())) conflict();
    OffsetDateTime completedAt = command.completedAt().atOffset(ZoneOffset.UTC);
    historyRepository.insertTransferEvent(
        command.productId(),
        command.transferRequestId(),
        command.completedAt().atZone(clock.getZone()).toLocalDate(),
        completedAt);
    historyRepository.makeAllPrivate(command.productId());
  }

  @Override
  @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
  public boolean hasCompletedTransfer(long productId, long transferRequestId) {
    return historyRepository
        .findTransferEvent(transferRequestId)
        .filter(event -> event.productId() == productId)
        .isPresent();
  }

  private void conflict() {
    throw new CommonException(ErrorCode.TRADE_CONFLICT);
  }
}
