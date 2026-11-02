package org.example.springtestci.product.dto;

import java.util.List;

/** 내 제품 목록과 페이지·요약 정보를 함께 반환한다. */
public record ProductPageResponse(List<ProductResponse> items, PageMetadata page, Summary summary) {

  public ProductPageResponse {
    items = List.copyOf(items);
  }

  public static ProductPageResponse of(
      List<ProductResponse> items, int number, int size, long total, long purchased) {
    long totalPages = total == 0 ? 0 : 1L + (total - 1L) / size;
    return new ProductPageResponse(
        items,
        new PageMetadata(number, size, total, totalPages, (long) number + 1 < totalPages),
        new Summary(total, purchased));
  }

  public record PageMetadata(
      int number, int size, long totalElements, long totalPages, boolean hasNext) {}

  public record Summary(long registered, long purchased) {}
}
