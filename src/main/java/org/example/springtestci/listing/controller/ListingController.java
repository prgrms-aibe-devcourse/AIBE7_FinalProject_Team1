package org.example.springtestci.listing.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.IOException;
import java.net.URI;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.listing.dto.CancelListingRequest;
import org.example.springtestci.listing.dto.ListingDetailResponse;
import org.example.springtestci.listing.dto.ListingManagementResponse;
import org.example.springtestci.listing.dto.ListingPageResponse;
import org.example.springtestci.listing.dto.ListingSearchRequest;
import org.example.springtestci.listing.dto.ListingStateResponse;
import org.example.springtestci.listing.dto.PublishListingRequest;
import org.example.springtestci.listing.dto.PublishedListingResponse;
import org.example.springtestci.listing.dto.UpdateListingRequest;
import org.example.springtestci.listing.service.ListingBrowseService;
import org.example.springtestci.listing.service.ListingDetailService;
import org.example.springtestci.listing.service.ListingManagementService;
import org.example.springtestci.listing.service.ListingPublishService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1")
public class ListingController {
  private final ListingPublishService publisher;
  private final ListingDetailService details;
  private final ListingBrowseService browse;
  private final ListingManagementService management;

  public ListingController(
      ListingPublishService publisher,
      ListingDetailService details,
      ListingBrowseService browse,
      ListingManagementService management) {
    this.publisher = publisher;
    this.details = details;
    this.browse = browse;
    this.management = management;
  }

  @GetMapping("/listings")
  public ResponseEntity<ApiResponse<ListingPageResponse>> list(
      @Valid @ModelAttribute ListingSearchRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(browse.list(request)));
  }

  @GetMapping("/listings/{listingId}/thumbnail")
  public ResponseEntity<Resource> thumbnail(@PathVariable @Positive long listingId)
      throws IOException {
    var image = browse.thumbnail(listingId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(image.contentType()))
        .body(image.resource());
  }

  @PostMapping("/products/{productId}/listings")
  public ResponseEntity<ApiResponse<PublishedListingResponse>> publish(
      @PathVariable @Positive long productId,
      Authentication authentication,
      @Valid @RequestBody PublishListingRequest request) {
    var result = publisher.publish(productId, userId(authentication), request);
    return ResponseEntity.status(result.created() ? 201 : 200)
        .location(URI.create("/api/v1/listings/" + result.response().listingId()))
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(result.response()));
  }

  @GetMapping("/listings/{listingId}")
  public ResponseEntity<ApiResponse<ListingDetailResponse>> detail(
      @PathVariable @Positive long listingId,
      Authentication authentication,
      @RequestParam(defaultValue = "0") @PositiveOrZero int page,
      @RequestParam(defaultValue = "20") @Positive @Max(100) int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(details.get(listingId, userId(authentication), page, size)));
  }

  @GetMapping("/listings/{listingId}/management")
  public ResponseEntity<ApiResponse<ListingManagementResponse>> management(
      @PathVariable @Positive long listingId, Authentication authentication) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(management.get(listingId, userId(authentication))));
  }

  @PutMapping("/listings/{listingId}")
  public ResponseEntity<ApiResponse<ListingManagementResponse>> update(
      @PathVariable @Positive long listingId,
      Authentication authentication,
      @Valid @RequestBody UpdateListingRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(management.update(listingId, userId(authentication), request)));
  }

  @PostMapping("/listings/{listingId}/cancel")
  public ResponseEntity<ApiResponse<ListingStateResponse>> cancel(
      @PathVariable @Positive long listingId,
      Authentication authentication,
      @Valid @RequestBody CancelListingRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(management.cancel(listingId, userId(authentication), request)));
  }

  @GetMapping("/listings/{listingId}/images/{imageId}")
  public ResponseEntity<Resource> image(
      @PathVariable @Positive long listingId,
      @PathVariable @Positive long imageId,
      Authentication authentication)
      throws IOException {
    userId(authentication);
    var image = details.image(listingId, imageId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(image.contentType()))
        .body(image.resource());
  }

  private long userId(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated())
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    try {
      long id = Long.parseLong(authentication.getName());
      if (id > 0) return id;
    } catch (NumberFormatException ignored) {
    }
    throw new CommonException(ErrorCode.UNAUTHORIZED);
  }
}
