package org.example.springtestci.listing.demo;

import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.entity.ListingStatus;
import org.example.springtestci.listing.service.ListingHistoryPrivatizer;
import org.example.springtestci.listing.service.ListingHistoryPublisher;
import org.example.springtestci.listing.service.ListingPublishConstraints;
import org.example.springtestci.listing.service.ListingTradeCanceller;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** 별도 로컬 샘플 DB에서만 게시 성공 흐름을 제공한다. 실제 거래·이전 모듈 확인을 대신하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("sale-draft-demo")
public class ListingPublishDemoConfig {
  @Bean
  ListingPublishConstraints listingPublishDemoConstraints() {
    return productId -> new ListingPublishConstraints.Snapshot(true, false, false);
  }

  @Bean
  ListingHistoryPublisher listingHistoryDemoPublisher(JdbcTemplate jdbc) {
    return (productId, listingId, sellerId) ->
        jdbc.update(
            "UPDATE product_events SET visibility = 'PUBLIC' WHERE product_id = ?", productId);
  }

  /** 이 샘플 DB에는 구매 요청·이전이 없다. 예약 취소를 처리했다고 가장하지 않는다. */
  @Bean
  ListingTradeCanceller listingTradeDemoCanceller() {
    return command -> {
      if (command.status() != ListingStatus.ACTIVE)
        throw new CommonException(ErrorCode.LISTING_CANCEL_UNAVAILABLE);
    };
  }

  @Bean
  ListingHistoryPrivatizer listingHistoryDemoPrivatizer(JdbcTemplate jdbc) {
    return (productId, listingId, sellerId) ->
        jdbc.update(
            "UPDATE product_events SET visibility = 'PRIVATE' WHERE product_id = ?", productId);
  }
}
