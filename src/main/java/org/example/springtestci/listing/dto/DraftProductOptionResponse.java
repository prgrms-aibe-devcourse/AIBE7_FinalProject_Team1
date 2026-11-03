package org.example.springtestci.listing.dto;

import java.time.LocalDate;
import java.util.List;
import org.example.springtestci.listing.entity.ListingStatus;
import org.example.springtestci.listing.repository.ListingRepository;
import org.example.springtestci.product.dto.ProductResponse;

/** 소유 제품 선택 전에 열린 판매글을 안내한다. 작성 가능 여부는 선택·게시 시 서버에서 다시 검사한다. */
public record DraftProductOptionResponse(
    long productId,
    String title,
    String manufacturer,
    String modelName,
    LocalDate purchaseDate,
    LocalDate warrantyExpiresAt,
    DraftProductResponse.Specifications specifications,
    List<DraftProductResponse.Image> images,
    Long listingId,
    ListingStatus listingStatus,
    boolean canCompose) {
  public static DraftProductOptionResponse from(
      ProductResponse product, ListingRepository.OpenListing listing) {
    var safe = DraftProductResponse.from(product);
    return new DraftProductOptionResponse(
        product.productId(),
        product.title(),
        product.manufacturer(),
        product.modelName(),
        safe.purchaseDate(),
        safe.warrantyExpiresAt(),
        safe.specifications(),
        safe.images(),
        listing == null ? null : listing.getListingId(),
        listing == null ? null : listing.getStatus(),
        listing == null);
  }
}
