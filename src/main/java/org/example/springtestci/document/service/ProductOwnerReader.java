package org.example.springtestci.document.service;

import java.util.Optional;

/** 제품의 현재 소유자를 조회한다. 제품 도메인 서비스가 생기면 그쪽 구현으로 교체한다. */
public interface ProductOwnerReader {

  Optional<Long> findOwnerId(Long productId);

  /** 제품 행을 잠그고 현재 소유자를 조회한다. 이력 등록처럼 제품을 먼저 잠그는 흐름과 잠금 순서를 맞출 때 쓴다. 호출한 트랜잭션이 끝날 때까지 잠금이 유지된다. */
  Optional<Long> lockOwnerId(Long productId);
}
