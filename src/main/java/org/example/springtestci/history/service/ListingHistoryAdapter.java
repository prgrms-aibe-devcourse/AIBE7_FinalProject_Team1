package org.example.springtestci.history.service;

import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.repository.JdbcListingHistoryReader;
import org.example.springtestci.listing.service.ListingHistoryPrivatizer;
import org.example.springtestci.listing.service.ListingHistoryPublisher;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 누적 제품 이력을 판매글의 공개 이력 계약에 연결한다.
 *
 * <p>정식 JDBC Reader와 같은 필드별 자동 공개 검사 계약을 사용한다. ADMIN 승인이나 과거 검토 플래그를 요구하지 않는다. 공개·비공개 변경은 호출자의 게시
 * 또는 판매 중지 트랜잭션에 그대로 참여한다.
 */
@Component
@Profile("!sale-draft-demo")
@ConditionalOnMissingBean({
  ListingHistoryReader.class,
  ListingHistoryPublisher.class,
  ListingHistoryPrivatizer.class
})
public class ListingHistoryAdapter
    implements ListingHistoryReader, ListingHistoryPublisher, ListingHistoryPrivatizer {

  private final JdbcTemplate jdbc;
  private final ListingHistoryReader reader;

  public ListingHistoryAdapter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.reader = new JdbcListingHistoryReader(jdbc);
  }

  /** 호출자의 제품 잠금 안에서 같은 조회 한도·미배포 구분·자동 공개 검사 계약을 적용한다. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Snapshot read(long productId) {
    return reader.read(productId);
  }

  /** 게시글 생성과 같은 트랜잭션에서 현재 판매자의 누적 이력을 공개 상태로 바꾼다. */
  @Override
  public void publish(long productId, long listingId, long sellerId) {
    if (!matchesListingOwner(productId, listingId, sellerId)) unavailable();
    jdbc.update("UPDATE product_events SET visibility = 'PUBLIC' WHERE product_id = ?", productId);
  }

  /** 마지막 열린 판매글의 종료와 같은 트랜잭션에서 누적 이력을 비공개로 되돌린다. */
  @Override
  public void makePrivate(long productId, long listingId, long sellerId) {
    if (!matchesListingOwner(productId, listingId, sellerId) || hasOpenListing(productId)) {
      unavailable();
    }
    jdbc.update("UPDATE product_events SET visibility = 'PRIVATE' WHERE product_id = ?", productId);
  }

  private boolean matchesListingOwner(long productId, long listingId, long sellerId) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
              FROM listings l
              JOIN products p ON p.id = l.product_id
             WHERE l.id = ?
               AND l.product_id = ?
               AND l.seller_id = ?
               AND p.owner_id = ?
            """,
            Integer.class,
            listingId,
            productId,
            sellerId,
            sellerId);
    return count != null && count == 1;
  }

  private boolean hasOpenListing(long productId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM listings WHERE product_id = ? AND status IN ('ACTIVE', 'RESERVED')",
            Integer.class,
            productId);
    return count != null && count > 0;
  }

  private void unavailable() {
    throw new CommonException(ErrorCode.LISTING_HISTORY_UNAVAILABLE);
  }
}
