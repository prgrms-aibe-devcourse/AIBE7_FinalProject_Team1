package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import org.example.springtestci.listing.repository.ListingDraftQueryRepository;
import org.example.springtestci.listing.repository.ListingDraftRepository;
import org.example.springtestci.listing.service.ListingDraftService;
import org.example.springtestci.product.dto.ProductPageResponse;
import org.example.springtestci.product.dto.ProductResponse;
import org.example.springtestci.product.service.ProductService;
import org.junit.jupiter.api.Test;

class ListingDraftServiceTest {

  @Test
  void saleDraftSelectorLoadsEveryOwnedProductPage() {
    ProductService products = mock(ProductService.class);
    ListingDraftService service =
        new ListingDraftService(
            mock(ListingDraftRepository.class), products, mock(ListingDraftQueryRepository.class));
    ProductResponse first = product(101L);
    when(products.listMine(7L, 0, 100, null, null))
        .thenReturn(ProductPageResponse.of(List.of(first), 0, 100, 101, 1, true));
    when(products.listMine(7L, 1, 100, first.createdAt(), first.productId()))
        .thenReturn(ProductPageResponse.of(List.of(product(102L)), 1, 100, 101, 1, false));

    var response = service.products(7L);

    assertEquals(List.of(101L, 102L), response.stream().map(item -> item.productId()).toList());
    verify(products).listMine(7L, 0, 100, null, null);
    verify(products).listMine(7L, 1, 100, first.createdAt(), first.productId());
  }

  private static ProductResponse product(long id) {
    return new ProductResponse(
        id,
        "제품 " + id,
        "제조사",
        "모델",
        null,
        null,
        null,
        1L,
        null,
        "UNKNOWN",
        null,
        List.of(),
        OffsetDateTime.parse("2026-11-02T00:00:00Z"));
  }
}
