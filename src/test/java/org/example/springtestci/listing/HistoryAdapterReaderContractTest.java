package org.example.springtestci.listing;

import org.example.springtestci.history.service.ListingHistoryAdapter;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.springframework.jdbc.core.JdbcTemplate;

/** 일반 history 어댑터에도 동일한 실제 SQL·범위·한도·자동 공개 검사 계약을 적용한다. */
class HistoryAdapterReaderContractTest extends JdbcListingHistoryReaderTest {
  @Override
  protected ListingHistoryReader createReader(JdbcTemplate jdbc) {
    return new ListingHistoryAdapter(jdbc);
  }
}
