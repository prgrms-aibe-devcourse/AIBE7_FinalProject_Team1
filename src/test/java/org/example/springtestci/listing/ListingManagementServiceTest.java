package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.example.springtestci.common.error.*;
import org.example.springtestci.listing.dto.CancelListingRequest;
import org.example.springtestci.listing.entity.*;
import org.example.springtestci.listing.repository.*;
import org.example.springtestci.listing.service.*;
import org.example.springtestci.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

class ListingManagementServiceTest {
  @Test
  void noCancellationAdaptersCannotBeAssumedSafe() {
    missingAdapter(false, false);
  }

  @Test
  void tradeAdapterAloneCannotCompleteCancellation() {
    missingAdapter(true, false);
  }

  @Test
  void historyAdapterAloneCannotCompleteCancellation() {
    missingAdapter(false, true);
  }

  @SuppressWarnings("unchecked")
  private void missingAdapter(boolean hasTrade, boolean hasHistory) {
    var listings = mock(ListingRepository.class);
    var products = mock(ProductService.class);
    var audit = mock(ListingAuditRepository.class);
    var access = mock(ListingRepository.Access.class);
    when(access.getSellerId()).thenReturn(1L);
    when(access.getProductId()).thenReturn(101L);
    when(listings.findAccessById(11)).thenReturn(Optional.of(access));
    var listing = mock(Listing.class);
    when(listing.getSellerId()).thenReturn(1L);
    when(listing.getStatus()).thenReturn(ListingStatus.ACTIVE);
    when(listings.findLockedById(11)).thenReturn(Optional.of(listing));
    ObjectProvider<ListingTradeCanceller> trades = mock(ObjectProvider.class);
    ObjectProvider<ListingHistoryPrivatizer> histories = mock(ObjectProvider.class);
    var trade = mock(ListingTradeCanceller.class);
    var history = mock(ListingHistoryPrivatizer.class);
    if (hasTrade) when(trades.getIfAvailable()).thenReturn(trade);
    if (hasHistory) when(histories.getIfAvailable()).thenReturn(history);
    var service =
        new ListingManagementService(
            listings, products, audit, trades, histories, JsonMapper.builder().build());
    var error =
        assertThrows(
            CommonException.class,
            () -> service.cancel(11, 1, new CancelListingRequest(0L, "reason")));
    assertEquals(ErrorCode.LISTING_CANCEL_UNAVAILABLE, error.errorCode());
    verify(products).lockOwnedForListing(1, 101);
    verify(listing, never()).cancel();
    verify(listings, never()).flush();
    verifyNoInteractions(audit, trade, history);
  }
}
