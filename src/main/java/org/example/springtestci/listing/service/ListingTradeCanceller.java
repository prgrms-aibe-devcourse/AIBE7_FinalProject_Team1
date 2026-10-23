package org.example.springtestci.listing.service;

import org.example.springtestci.listing.entity.ListingStatus;

/**
 * 판매글 자체를 중지할 때 거래 도메인이 구현할 계약. 호출자의 같은 DB·트랜잭션과 제품 → 판매글 잠금에 참여한다. PENDING 구매 요청을 모두 취소하고
 * RESERVED이면 수락된 거래·대기 중 내부 이전도 취소하며 링크·QR을 무효화한다. 취소 사유·수행자·시각을 기록하고, 완료된 거래나 불완전한 연동은 예외로 거부한다.
 * REQUIRES_NEW·비동기·외부 HTTP로 분리하거나 처리할 데이터가 없다고 추정해 성공하면 안 된다.
 */
public interface ListingTradeCanceller {
  void cancel(Command command);

  record Command(
      long productId, long listingId, long sellerId, ListingStatus status, String reason) {}
}
