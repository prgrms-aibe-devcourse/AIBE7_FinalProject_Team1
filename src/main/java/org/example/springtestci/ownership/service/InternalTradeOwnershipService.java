package org.example.springtestci.ownership.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.history.service.InternalTransferHistoryWriter;
import org.example.springtestci.ownership.event.OwnershipTransferred;
import org.example.springtestci.ownership.repository.InternalTradeOwnershipRepository;
import org.example.springtestci.ownership.repository.InternalTradeOwnershipRepository.Transfer;
import org.example.springtestci.product.event.ProductFactsChanged;
import org.example.springtestci.trade.repository.TradeAuditRepository;
import org.example.springtestci.trade.service.InternalTradeCompletionPort;
import org.example.springtestci.trade.service.InternalTransferPendingWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 실제 내부 이전 준비·완료 구현이다. 거래 측의 제품→판매글→구매 요청 잠금과 같은 트랜잭션에서만 실행한다. */
@Service
@ConditionalOnProperty(
    prefix = "app.features.trade-purchase-requests",
    name = "enabled",
    havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
public class InternalTradeOwnershipService
    implements InternalTransferPendingWriter, InternalTradeCompletionPort {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final InternalTradeOwnershipRepository repository;
  private final TradeAuditRepository audit;
  private final InternalTransferHistoryWriter historyWriter;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public InternalTradeOwnershipService(
      InternalTradeOwnershipRepository repository,
      TradeAuditRepository audit,
      InternalTransferHistoryWriter historyWriter,
      ApplicationEventPublisher events,
      Clock clock) {
    this.repository = repository;
    this.audit = audit;
    this.historyWriter = historyWriter;
    this.events = events;
    this.clock = clock;
  }

  /** P2의 실제 예약 관계를 확인하고 INTERNAL/PENDING을 만든다. 소유자 변경·링크 발급은 하지 않는다. */
  @Override
  public void createPending(InternalTransferPendingWriter.Command c) {
    if (c == null
        || !validParties(c.productId(), c.purchaseRequestId(), c.fromUserId(), c.toUserId())
        || !"INTERNAL".equals(c.transferType())
        || !"PENDING".equals(c.status())) throw conflict();
    try {
      var product =
          repository
              .lockProduct(c.productId())
              .orElseThrow(InternalTradeOwnershipService::conflict);
      if (product.archived()
          || product.ownerId() != c.fromUserId()
          || !repository.matchesReservation(c)) throw conflict();
      var existing = repository.transfersForRequest(c.purchaseRequestId());
      if (!existing.isEmpty()) {
        if (existing.size() != 1
            || !matches(existing.get(0), c.productId(), c.fromUserId(), c.toUserId())
            || !"PENDING".equals(existing.get(0).status())) throw conflict();
        return;
      }
      repository.expireDirectRequests(c.productId(), clock.instant());
      long id = repository.insertPending(c, internalHash(), clock.instant());
      audit.log(
          c.productId(),
          c.fromUserId(),
          id,
          "OWNERSHIP_TRANSFER_CREATED",
          null,
          "status=PENDING",
          "내부 거래 이전 준비");
    } catch (DataIntegrityViolationException failure) {
      throw conflict();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  /** P4가 확정한 양측 확인·SOLD/COMPLETED를 재검사하고 이전·이력·증빙·초안 후처리를 함께 확정한다. */
  @Override
  public void complete(InternalTradeCompletionPort.Command c) {
    if (c == null
        || !validParties(c.productId(), c.purchaseRequestId(), c.fromUserId(), c.toUserId())
        || c.listingId() <= 0
        || c.confirmedFactsVersion() <= 0
        || c.completedAt() == null
        || (c.actorId() != c.fromUserId() && c.actorId() != c.toUserId())) throw conflict();
    try {
      var product =
          repository
              .lockProduct(c.productId())
              .orElseThrow(InternalTradeOwnershipService::conflict);
      var transfers = repository.transfersForRequest(c.purchaseRequestId());
      if (transfers.size() != 1
          || !matches(transfers.get(0), c.productId(), c.fromUserId(), c.toUserId())
          || !repository.matchesCompletion(c)) throw conflict();
      var transfer = transfers.get(0);
      // 완료 재시도는 후속 재이전·제품 보관과 무관하게 기존 결과를 확인하며 추가 쓰기를 하지 않는다.
      if ("COMPLETED".equals(transfer.status())) {
        if (!historyWriter.hasCompletedTransfer(c.productId(), transfer.id())) throw conflict();
        return;
      }
      if (!"PENDING".equals(transfer.status())
          || product.archived()
          || product.ownerId() != c.fromUserId()
          || product.factsVersion() != c.confirmedFactsVersion()) throw conflict();
      if (repository.completeTransfer(transfer.id(), c.completedAt()) != 1
          || repository.transferOwner(c) != 1) throw conflict();
      // 이력 포트가 TRANSFER 생성과 공개 종료를 함께 처리한다. 실패하면 소유자 변경도 롤백된다.
      historyWriter.recordCompletedTransfer(
          new InternalTransferHistoryWriter.Command(
              c.productId(), transfer.id(), c.fromUserId(), c.toUserId(), c.completedAt()));
      repository.protectDocuments(c.productId(), c.completedAt());
      // 필수 초안 정리는 동기 처리한다. 어느 후처리나 상위 거래 감사가 실패해도 전체를 롤백한다.
      events.publishEvent(new OwnershipTransferred(c.productId(), c.fromUserId(), c.toUserId()));
      audit.log(
          c.productId(),
          c.actorId(),
          transfer.id(),
          "OWNERSHIP_TRANSFERRED",
          "fromOwner=" + c.fromUserId(),
          "toOwner=" + c.toUserId(),
          "내부 거래 소유권 이전 완료");
      // SOLD와 새 소유권이 공개 종료를 결정한다. 커밋 후 AI 최신화는 종료 판매글에 접수되지 않는다.
      events.publishEvent(new ProductFactsChanged(c.productId()));
    } catch (DataIntegrityViolationException failure) {
      throw conflict();
    } catch (DataAccessException failure) {
      throw unavailable();
    }
  }

  /** 내부 거래 식별자는 양수여야 하며 보내는 사용자와 받는 사용자가 같을 수 없다. */
  private static boolean validParties(long product, long request, long from, long to) {
    return product > 0 && request > 0 && from > 0 && to > 0 && from != to;
  }

  /** 구매 요청에 연결된 이전이 같은 제품·양측 사용자의 INTERNAL 이전인지 검사한다. */
  private static boolean matches(Transfer t, long product, long from, long to) {
    return t.productId() == product
        && t.fromUserId() == from
        && Long.valueOf(to).equals(t.toUserId())
        && "INTERNAL".equals(t.type());
  }

  /** 외부에서 사용할 원문 토큰 없이 기존 필수 해시 컬럼을 채울 난수 해시를 만든다. */
  private static String internalHash() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static CommonException conflict() {
    return new CommonException(ErrorCode.TRADE_CONFLICT);
  }

  private static CommonException unavailable() {
    return new CommonException(ErrorCode.TRADE_DEPENDENCY_UNAVAILABLE);
  }
}
