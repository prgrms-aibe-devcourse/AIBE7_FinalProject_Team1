package org.example.springtestci.listing.service;

/**
 * 열린 판매글이 없어진 제품의 누적 핵심 이력을 같은 DB·호출 트랜잭션에서 PRIVATE로 전환한다. 이력·증빙 자체와 현재 소유자의 누적 조회 권한은 보존한다. 연동
 * 불가·실패 시 예외를 던져 판매 중지도 롤백한다.
 */
public interface ListingHistoryPrivatizer {
  void makePrivate(long productId, long listingId, long sellerId);
}
