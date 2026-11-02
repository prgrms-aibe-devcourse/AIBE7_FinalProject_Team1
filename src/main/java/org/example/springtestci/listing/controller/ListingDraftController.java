package org.example.springtestci.listing.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.listing.dto.DraftProductResponse;
import org.example.springtestci.listing.dto.ListingDraftPageResponse;
import org.example.springtestci.listing.dto.ListingDraftResponse;
import org.example.springtestci.listing.dto.ListingPreviewResponse;
import org.example.springtestci.listing.dto.ListingPublicationResponse;
import org.example.springtestci.listing.dto.SaveListingDraftRequest;
import org.example.springtestci.listing.service.ListingDraftService;
import org.example.springtestci.listing.service.ListingPreviewService;
import org.example.springtestci.listing.service.ListingPublicationService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 판매글 작성 화면의 요청을 받아 임시저장·공개 예정 정보·미리보기 서비스로 연결한다. */
@RestController
@Validated
@RequestMapping("/api/v1")
public class ListingDraftController {

  private final ListingDraftService service;
  private final ListingPublicationService publications;
  private final ListingPreviewService previews;

  public ListingDraftController(
      ListingDraftService service,
      ListingPublicationService publications,
      ListingPreviewService previews) {
    this.service = service;
    this.publications = publications;
    this.previews = previews;
  }

  /** 저장된 초안의 특정 버전으로 게시 전 모습을 확인한다. 실제 게시나 이력 공개는 하지 않는다. */
  @GetMapping("/products/{productId}/listing-draft/preview")
  public ResponseEntity<ApiResponse<ListingPreviewResponse>> preview(
      @PathVariable @Positive long productId,
      @RequestParam @Positive long draftId,
      @RequestParam @PositiveOrZero long version,
      Authentication authentication,
      @RequestParam(defaultValue = "0") @PositiveOrZero int page,
      @RequestParam(defaultValue = "20") @Positive @Max(100) int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.success(
                previews.get(productId, draftId, version, userId(authentication), page, size)));
  }

  /** 미리보기 JSON의 사진 URL로 요청하면, 선택된 제품 사진의 실제 파일을 반환한다. */
  @GetMapping("/products/{productId}/listing-draft/preview/images/{imageId}")
  public ResponseEntity<Resource> previewImage(
      @PathVariable @Positive long productId,
      @PathVariable @Positive long imageId,
      @RequestParam @Positive long draftId,
      @RequestParam @PositiveOrZero long version,
      Authentication authentication)
      throws IOException {
    var image = previews.image(productId, draftId, version, imageId, userId(authentication));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(image.contentType()))
        .body(image.resource());
  }

  /** 현재 소유자에게 공개 예정 제품 정보와 전체 누적 이력의 한 페이지를 제공한다. */
  @GetMapping("/products/{productId}/listing-draft/publication")
  public ResponseEntity<ApiResponse<ListingPublicationResponse>> publication(
      @PathVariable @Positive long productId,
      Authentication authentication,
      @RequestParam(defaultValue = "0") @PositiveOrZero int page,
      @RequestParam(defaultValue = "20") @Positive @Max(100) int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(publications.get(productId, userId(authentication), page, size)));
  }

  /** 이어서 작성할 글을 고를 수 있도록 내 임시저장본 목록을 페이지 단위로 반환한다. */
  @GetMapping("/listing-drafts")
  public ResponseEntity<ApiResponse<ListingDraftPageResponse>> list(
      Authentication authentication,
      @RequestParam(defaultValue = "0") @PositiveOrZero int page,
      @RequestParam(defaultValue = "20") @Positive @Max(100) int size) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(service.list(userId(authentication), page, size)));
  }

  /** 선택한 제품 한 개의 임시저장 내용과 사진 선택을 불러와 작성 폼을 복원한다. */
  @GetMapping("/products/{productId}/listing-draft")
  public ResponseEntity<ApiResponse<ListingDraftResponse>> get(
      @PathVariable @Positive long productId, Authentication authentication) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(service.get(productId, userId(authentication))));
  }

  /** 기존 초안의 ID·버전을 함께 받아 다른 탭의 최신 저장본을 덮어쓰지 않도록 수정한다. */
  @PutMapping("/products/{productId}/listing-draft")
  public ResponseEntity<ApiResponse<ListingDraftResponse>> update(
      @PathVariable @Positive long productId,
      @RequestParam @Positive long draftId,
      Authentication authentication,
      @Valid @RequestBody SaveListingDraftRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            ApiResponse.success(
                service.update(productId, draftId, userId(authentication), request)));
  }

  /** 임시저장본만 삭제한다. 제품 자체와 제품에 등록된 사진은 유지된다. */
  @DeleteMapping("/products/{productId}/listing-draft")
  public ResponseEntity<Void> delete(
      @PathVariable @Positive long productId,
      @RequestParam @Positive long draftId,
      @RequestParam @PositiveOrZero long version,
      Authentication authentication) {
    service.delete(productId, draftId, userId(authentication), version);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  /** 판매글 작성 화면의 제품 선택 목록에 사용할 현재 보유 제품들을 반환한다. */
  @GetMapping("/listing-draft-products")
  public ResponseEntity<ApiResponse<List<DraftProductResponse>>> products(
      Authentication authentication) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(service.products(userId(authentication))));
  }

  /** 제품 소유권과 중복 판매 여부를 확인한 뒤 작성에 필요한 사양·사진을 불러온다. */
  @GetMapping("/products/{productId}/listing-draft/composition")
  public ResponseEntity<ApiResponse<DraftProductResponse>> composition(
      @PathVariable @Positive long productId, Authentication authentication) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(service.composition(productId, userId(authentication))));
  }

  /** 해당 제품의 첫 초안을 비공개로 저장한다. 필수 게시 항목은 아직 비어 있어도 된다. */
  @PostMapping("/products/{productId}/listing-draft")
  public ResponseEntity<ApiResponse<ListingDraftResponse>> save(
      @PathVariable @Positive long productId,
      Authentication authentication,
      @Valid @RequestBody SaveListingDraftRequest request) {
    var saved = service.save(productId, userId(authentication), request);
    return ResponseEntity.created(URI.create("/api/v1/products/" + productId + "/listing-draft"))
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(saved));
  }

  @GetMapping("/products/{productId}/listing-draft/images/{imageId}")
  public ResponseEntity<Resource> image(
      @PathVariable @Positive long productId,
      @PathVariable @Positive long imageId,
      Authentication authentication)
      throws IOException {
    var image = service.image(productId, imageId, userId(authentication));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(image.contentType()))
        .body(image.resource());
  }

  // 작성자 ID를 요청 본문에서 받지 않고 인증 정보에서 꺼내 다른 사람 이름으로 저장하지 못하게 한다.
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
