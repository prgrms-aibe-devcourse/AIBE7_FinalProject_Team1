package org.example.springtestci.listing.config;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.example.springtestci.listing.service.ListingHistoryPrivatizer;
import org.example.springtestci.listing.service.ListingHistoryPublisher;
import org.example.springtestci.listing.service.ListingPublishConstraints;
import org.example.springtestci.listing.service.ListingTradeCanceller;
import org.example.springtestci.trade.service.InternalTransferPendingWriter;
import org.example.springtestci.trade.service.PublishedListing;
import org.example.springtestci.trade.service.PublishedListingAccess;
import org.example.springtestci.trade.service.PublishedListingStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** 로컬/운영 실행 환경에서 이력·거래 연동 모듈 없이도 판매글 작성·게시·취소가 가능하도록 기본 빈을 제공한다. */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class ListingDefaultConfig {

  @Bean
  @ConditionalOnMissingBean(ListingPublishConstraints.class)
  ListingPublishConstraints defaultListingPublishConstraints() {
    return productId -> new ListingPublishConstraints.Snapshot(true, false, false);
  }

  @Bean
  @ConditionalOnMissingBean(ListingHistoryPublisher.class)
  ListingHistoryPublisher defaultListingHistoryPublisher() {
    return (productId, listingId, sellerId) -> {};
  }

  @Bean
  @ConditionalOnMissingBean(ListingTradeCanceller.class)
  ListingTradeCanceller defaultListingTradeCanceller() {
    return command -> {};
  }

  @Bean
  @ConditionalOnMissingBean(ListingHistoryPrivatizer.class)
  ListingHistoryPrivatizer defaultListingHistoryPrivatizer() {
    return (productId, listingId, sellerId) -> {};
  }

  @Bean
  @ConditionalOnMissingBean(PublishedListingAccess.class)
  PublishedListingAccess defaultPublishedListingAccess(JdbcTemplate jdbc) {
    return new PublishedListingAccess() {
      @Override
      public Optional<PublishedListing> findById(long listingId) {
        return jdbc
            .query(
                "SELECT id, product_id, seller_id, status FROM listings WHERE id = ?",
                (rs, i) ->
                    new PublishedListing(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getLong("seller_id"),
                        rs.getLong("seller_id"),
                        PublishedListingStatus.valueOf(rs.getString("status"))),
                listingId)
            .stream()
            .findFirst();
      }

      @Override
      public Optional<PublishedListing> findByIdForUpdate(long listingId) {
        return jdbc
            .query(
                "SELECT id, product_id, seller_id, status FROM listings WHERE id = ? FOR UPDATE",
                (rs, i) ->
                    new PublishedListing(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getLong("seller_id"),
                        rs.getLong("seller_id"),
                        PublishedListingStatus.valueOf(rs.getString("status"))),
                listingId)
            .stream()
            .findFirst();
      }

      @Override
      public Map<Long, PublishedListingStatus> findStatuses(Collection<Long> listingIds) {
        if (listingIds.isEmpty()) return Map.of();
        String inSql = String.join(",", Collections.nCopies(listingIds.size(), "?"));
        return jdbc.query(
            "SELECT id, status FROM listings WHERE id IN (" + inSql + ")",
            ps -> {
              int idx = 1;
              for (Long id : listingIds) ps.setLong(idx++, id);
            },
            rs -> {
              Map<Long, PublishedListingStatus> map = new HashMap<>();
              while (rs.next()) {
                map.put(rs.getLong("id"), PublishedListingStatus.valueOf(rs.getString("status")));
              }
              return map;
            });
      }

      @Override
      public void reserve(long listingId) {
        jdbc.update(
            "UPDATE listings SET status = 'RESERVED' WHERE id = ? AND status = 'ACTIVE'",
            listingId);
      }

      @Override
      public void releaseReservation(long listingId) {
        jdbc.update(
            "UPDATE listings SET status = 'ACTIVE' WHERE id = ? AND status = 'RESERVED'",
            listingId);
      }

      @Override
      public void sell(long listingId) {
        jdbc.update(
            "UPDATE listings SET status = 'SOLD', active_slot = null WHERE id = ? AND status = 'RESERVED'",
            listingId);
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(InternalTransferPendingWriter.class)
  InternalTransferPendingWriter defaultInternalTransferPendingWriter(JdbcTemplate jdbc) {
    return command -> {
      jdbc.update(
          "INSERT INTO ownership_transfers (product_id, purchase_request_id, from_user_id, to_user_id, transfer_type, status, pending_slot, token_hash, expires_at) VALUES (?, ?, ?, ?, 'INTERNAL', 'PENDING', 1, ?, ?)",
          command.productId(),
          command.purchaseRequestId(),
          command.fromUserId(),
          command.toUserId(),
          "internal-" + command.purchaseRequestId(),
          java.time.Instant.now().plus(24, java.time.temporal.ChronoUnit.HOURS));
    };
  }
}
