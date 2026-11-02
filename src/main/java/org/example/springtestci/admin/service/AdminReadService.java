package org.example.springtestci.admin.service;

import org.example.springtestci.admin.dto.AdminDtos.*;
import org.example.springtestci.admin.repository.AdminReadRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 조회는 쓰기·강제 취소·소유권 변경 서비스를 호출하지 않는다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminReadService {
  private final AdminAccessService access;
  private final AdminReadRepository repository;
  private final AdminAuditFacts facts;
  private final boolean tradeEnabled;

  public AdminReadService(
      AdminAccessService access,
      AdminReadRepository repository,
      AdminAuditFacts facts,
      @Value("${app.features.trade-purchase-requests.enabled:false}") boolean tradeEnabled) {
    this.access = access;
    this.repository = repository;
    this.facts = facts;
    this.tradeEnabled = tradeEnabled;
  }

  /** 관리자와 검색 조건을 검증하고 같은 읽기 트랜잭션에서 목록 및 전체 상태 요약을 반환한다. */
  public TradePage trades(long adminId, Filter filter) {
    requireTrade(adminId);
    filter.validate();
    return repository.trades(filter);
  }

  public Trade trade(long adminId, long id) {
    requireTrade(adminId);
    return repository.trade(id);
  }

  public Page<Audit> audits(long adminId, Filter filter) {
    access.requireAdmin(adminId);
    filter.validate();
    return repository.audits(filter);
  }

  public AuditDetail audit(long adminId, long id) {
    access.requireAdmin(adminId);
    var source = repository.audit(id);
    return new AuditDetail(
        source.record(), source.reason(), facts.read(source.before()), facts.read(source.after()));
  }

  private void requireTrade(long id) {
    access.requireAdmin(id);
    if (!tradeEnabled) throw new CommonException(ErrorCode.ADMIN_TRADE_UNAVAILABLE);
  }
}
