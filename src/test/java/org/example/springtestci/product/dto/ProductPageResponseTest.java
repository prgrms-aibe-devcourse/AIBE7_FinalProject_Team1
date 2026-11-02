package org.example.springtestci.product.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProductPageResponseTest {
  @Test
  void registeredAndPageTotalRepresentAllOwnedProductsIncludingPurchasedProducts() {
    var response = ProductPageResponse.of(List.of(), 0, 3, 12, 4, false);
    assertEquals(12, response.page().totalElements());
    assertEquals(12, response.summary().registered());
    assertEquals(4, response.summary().purchased());
  }
}
