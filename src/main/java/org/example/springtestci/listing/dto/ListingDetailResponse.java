package org.example.springtestci.listing.dto;

import java.time.Instant;
import java.util.List;
import org.example.springtestci.listing.entity.ListingStatus;

/** 회원용 공개 상세. 원래 초안·판매자 계정·비공개 제품 필드는 제공하지 않는다. */
public record ListingDetailResponse(
    long listingId,
    long contentVersion,
    ListingStatus status,
    Instant publishedAt,
    ListingPreviewResponse.Content content,
    List<ListingPreviewResponse.Image> images,
    ListingPublicationResponse.Product product,
    ListingPublicationResponse.History history,
    boolean canManage) {
  public ListingDetailResponse {
    images = List.copyOf(images);
  }
}
