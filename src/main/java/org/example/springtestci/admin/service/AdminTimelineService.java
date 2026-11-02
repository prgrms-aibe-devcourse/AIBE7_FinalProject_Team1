package org.example.springtestci.admin.service;

import org.example.springtestci.admin.dto.AdminDtos.TradeTimeline;
import org.example.springtestci.admin.repository.AdminTimelineRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 거래 조회와 같은 권한·기능 조건을 적용하고 사건 목록을 읽기 전용 스냅샷으로 제공한다. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminTimelineService {
  private final AdminReadService trades;
  private final AdminTimelineRepository repository;
  private final boolean reviewsEnabled;

  public AdminTimelineService(
      AdminReadService trades,
      AdminTimelineRepository repository,
      @Value("${app.features.trade-p3.enabled:false}") boolean reviewsEnabled) {
    this.trades = trades;
    this.repository = repository;
    this.reviewsEnabled = reviewsEnabled;
  }

  /** 거래 ID·페이지를 검증하고, 현재 관리자 역할과 거래 존재를 확인한 뒤 실제 연결 기록만 반환한다. */
  public TradeTimeline timeline(long adminId, long id, int page, int size) {
    trades.trade(adminId, id);
    if (id < 1 || page < 0 || page > 100_000 || size < 1 || size > 100) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    return new TradeTimeline(
        id, repository.timeline(id, page, size, reviewsEnabled), reviewsEnabled);
  }
}
