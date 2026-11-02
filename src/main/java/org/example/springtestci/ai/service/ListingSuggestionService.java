package org.example.springtestci.ai.service;

import java.time.Clock;
import java.time.Instant;
import org.example.springtestci.ai.dto.ListingSuggestionResponse;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 생성 전후 권한·자료를 검사하고 제안만 반환한다. 초안·판매글·이력 공개 상태는 변경하지 않는다. */
@Service
public class ListingSuggestionService {
  private final ListingSuggestionSource sources;
  private final ListingSuggestionGateway gateway;
  private final AiSuggestionRateLimiter limits;
  private final AiListingTextPolicy policy;
  private final Clock clock;

  public ListingSuggestionService(
      ListingSuggestionSource sources,
      ListingSuggestionGateway gateway,
      AiSuggestionRateLimiter limits,
      AiListingTextPolicy policy,
      Clock clock) {
    this.sources = sources;
    this.gateway = gateway;
    this.limits = limits;
    this.policy = policy;
    this.clock = clock;
  }

  /** 자료 수집 → 요청 제한 → AI 생성·검증 순서로 제안을 반환한다. 외부 응답 대기 중에는 DB 트랜잭션을 유지하지 않는다. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public ListingSuggestionResponse generate(long productId, long userId) {
    var before = sources.capture(productId, userId);
    policy.validateInput(before.json());
    if (!gateway.configured()) throw new CommonException(ErrorCode.AI_UNAVAILABLE);
    var lease = limits.acquire(userId);
    try {
      var result = policy.validateSuggestion(gateway.generate(before.json()), before.json());
      // 생성 중 소유권·자료가 바뀌었는지 다시 확인해 예전 정보로 만든 제안을 반환하지 않는다.
      var after = sources.capture(productId, userId);
      if (!before.sourceVersion().equals(after.sourceVersion()))
        throw new CommonException(ErrorCode.AI_SOURCE_CHANGED);
      return new ListingSuggestionResponse(
          productId,
          before.factsVersion(),
          before.sourceVersion(),
          result.title(),
          result.description(),
          Instant.now(clock),
          before.sample());
    } finally {
      limits.release(lease);
    }
  }
}
