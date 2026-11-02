package org.example.springtestci.admin.service;

import static org.example.springtestci.admin.dto.AdminDtos.ConfirmationState.*;
import static org.example.springtestci.admin.dto.AdminDtos.ProgressState.*;

import org.example.springtestci.admin.dto.AdminDtos.*;
import org.example.springtestci.admin.repository.AdminProgressRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자에게 현재 확인 대기 단계를 안내한다. 당사자 전용 완료 API나 상태 변경 서비스를 호출하지 않는다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminProgressService {
  private final AdminReadService trades;
  private final AdminProgressRepository repository;
  private final boolean confirmationsEnabled;

  public AdminProgressService(
      AdminReadService trades,
      AdminProgressRepository repository,
      @Value("${app.features.trade-p3.enabled:false}") boolean reviewsEnabled,
      @Value("${app.features.trade-p4.enabled:false}") boolean completionEnabled) {
    this.trades = trades;
    this.repository = repository;
    this.confirmationsEnabled = reviewsEnabled && completionEnabled;
  }

  /** 현재 관리자 역할·P2 활성·거래 존재를 먼저 확인한다. 종료 거래는 이후 제품 버전과 비교하지 않는다. */
  public TradeProgress progress(long adminId, long requestId) {
    Trade trade = trades.trade(adminId, requestId);
    String request = trade.requestStatus(), transaction = trade.transactionStatus();
    if ("ACCEPTED".equals(request) && "COMPLETED".equals(transaction)) {
      return withoutConfirmations(requestId, COMPLETED, NOT_APPLICABLE);
    }
    if ("CANCELLED".equals(request) && (transaction == null || "CANCELLED".equals(transaction))) {
      return withoutConfirmations(requestId, CANCELLED, NOT_APPLICABLE);
    }
    if ("REJECTED".equals(request) && transaction == null) {
      return withoutConfirmations(requestId, REJECTED, NOT_APPLICABLE);
    }
    if ("PENDING".equals(request) && transaction == null) {
      return withoutConfirmations(requestId, WAITING_SELLER_ACCEPTANCE, NOT_APPLICABLE);
    }
    if (!"ACCEPTED".equals(request) || !"RESERVED".equals(transaction)) {
      return withoutConfirmations(requestId, CONNECTION_CHECK_REQUIRED, UNAVAILABLE);
    }
    // 꺼진 기능의 테이블을 참조하거나, 제공되지 않는 확인 상태를 '미확인 0건'으로 표시하지 않는다.
    if (!confirmationsEnabled) {
      return withoutConfirmations(requestId, CONFIRMATION_UNAVAILABLE, UNAVAILABLE);
    }
    var source = repository.find(requestId).orElse(null);
    if (source == null
        || !source.connected()
        || source.factsVersion() == null
        || source.factsVersion() < 1
        || invalidVersion(source.sellerConfirmedVersion(), source.factsVersion())
        || invalidVersion(source.buyerConfirmedVersion(), source.factsVersion())) {
      return withoutConfirmations(requestId, CONNECTION_CHECK_REQUIRED, UNAVAILABLE);
    }
    ConfirmationState seller = confirmation(source.sellerConfirmedVersion(), source.factsVersion());
    ConfirmationState buyer = confirmation(source.buyerConfirmedVersion(), source.factsVersion());
    ProgressState state;
    if (source.unreviewedCount() > 0) state = WAITING_BUYER_CHANGE_REVIEW;
    else if (seller != CURRENT && buyer != CURRENT) state = WAITING_BOTH_CONFIRMATIONS;
    else if (seller != CURRENT) state = WAITING_SELLER_CONFIRMATION;
    else if (buyer != CURRENT) state = WAITING_BUYER_CONFIRMATION;
    // 정상 완료는 같은 트랜잭션에서 확정된다. 양측 확인만으로 완료됐다고 추정하지 않는다.
    else state = CONFIRMATIONS_RECEIVED;
    return new TradeProgress(
        requestId,
        state,
        seller,
        buyer,
        source.unreviewedCount(),
        source.factsVersion(),
        source.sellerConfirmedVersion(),
        source.buyerConfirmedVersion());
  }

  private boolean invalidVersion(Long confirmed, long current) {
    return confirmed != null && (confirmed < 1 || confirmed > current);
  }

  private ConfirmationState confirmation(Long confirmed, long current) {
    if (confirmed == null) return NOT_CONFIRMED;
    return confirmed == current ? CURRENT : RECONFIRM_REQUIRED;
  }

  private TradeProgress withoutConfirmations(
      long id, ProgressState state, ConfirmationState confirmation) {
    return new TradeProgress(id, state, confirmation, confirmation, null, null, null, null);
  }
}
