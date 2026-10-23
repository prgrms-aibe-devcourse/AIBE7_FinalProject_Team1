package org.example.springtestci.listing.dto;

import java.util.List;
import org.example.springtestci.listing.entity.ListingStatus;

/** 게시 당시 판매자이면서 현재 소유자인 회원만 받는 편집 정보다. */
public record ListingManagementResponse(
    long listingId,
    long productId,
    long contentVersion,
    ListingStatus status,
    ListingPreviewResponse.Content content,
    List<Long> selectedImageIds,
    List<ListingPreviewResponse.Image> availableImages,
    boolean canEdit,
    boolean canCancel) {
  public ListingManagementResponse {
    selectedImageIds = List.copyOf(selectedImageIds);
    availableImages = List.copyOf(availableImages);
  }
}
