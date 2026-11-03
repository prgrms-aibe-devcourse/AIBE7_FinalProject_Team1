package org.example.springtestci.listing.repository;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.example.springtestci.listing.entity.Listing;
import org.example.springtestci.listing.entity.ListingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** 게시글 저장과 중복 게시·열린 판매글·행 잠금 조회에 사용하는 JPA 저장소다. */
public interface ListingRepository extends JpaRepository<Listing, Long> {
  Optional<Listing> findBySourceDraftId(long sourceDraftId);

  boolean existsByProductIdAndStatusIn(long productId, Collection<ListingStatus> statuses);

  /** 본인 판매글의 열린 상태를 한 번에 읽어 제품별 추가 조회를 피한다. */
  @Query(
      "select l.productId as productId, l.id as listingId, l.status as status from Listing l "
          + "where l.sellerId = :sellerId and l.status in :statuses")
  List<OpenListing> findOpenBySellerId(long sellerId, Collection<ListingStatus> statuses);

  interface OpenListing {
    long getProductId();

    long getListingId();

    ListingStatus getStatus();
  }

  // 소유권 검사 전에 엔티티를 읽으면 1차 캐시에 변경 전 상태가 남을 수 있어 식별자만 읽는다.
  @Query("select l.productId as productId, l.sellerId as sellerId from Listing l where l.id = :id")
  Optional<Access> findAccessById(long id);

  /** 제품 잠금을 먼저 획득한 뒤 최신 판매 상태를 읽는다. 거래의 예약 처리도 같은 순서를 따른다. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from Listing l where l.id = :id")
  Optional<Listing> findLockedById(long id);

  interface Access {
    long getProductId();

    long getSellerId();
  }
}
