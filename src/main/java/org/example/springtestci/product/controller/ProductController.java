package org.example.springtestci.product.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.time.OffsetDateTime;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.product.dto.CreateProductRequest;
import org.example.springtestci.product.dto.ProductPageResponse;
import org.example.springtestci.product.dto.ProductResponse;
import org.example.springtestci.product.dto.UpdateProductImagesRequest;
import org.example.springtestci.product.dto.UpdateProductRequest;
import org.example.springtestci.product.service.ProductService;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 제품 HTTP 요청을 받는 진입점이다.
 *
 * <p>요청값을 DTO로 받은 뒤 실제 업무 처리는 {@link ProductService}에 위임하고, 처리 결과를 공통 응답 형식으로 반환한다.
 */
@Validated
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

  // Controller가 DB에 직접 접근하지 않도록 제품 업무를 담당하는 Service를 주입받는다.
  private final ProductService productService;

  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  /** 로그인 사용자의 제품을 등록하고 HTTP 201 Created를 반환한다. */
  @PostMapping
  public ResponseEntity<ApiResponse<ProductResponse>> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateProductRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(productService.create(userId(jwt), request)));
  }

  /** 로그인 사용자가 소유한 제품 목록을 조회한다. */
  @GetMapping
  public ApiResponse<ProductPageResponse> listMine(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime cursorCreatedAt,
      @RequestParam(required = false) @Positive Long cursorProductId) {
    return ApiResponse.success(
        productService.listMine(userId(jwt), page, size, cursorCreatedAt, cursorProductId));
  }

  /** productId에 해당하는 내 제품 한 건을 조회한다. */
  @GetMapping("/{productId}")
  public ApiResponse<ProductResponse> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long productId) {
    return ApiResponse.success(productService.get(userId(jwt), productId));
  }

  /** 제품의 수정 가능한 기본 정보를 변경한다. */
  @PatchMapping("/{productId}")
  public ApiResponse<ProductResponse> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @Valid @RequestBody UpdateProductRequest request) {
    return ApiResponse.success(productService.update(userId(jwt), productId, request));
  }

  @DeleteMapping("/{productId}")
  public ResponseEntity<Void> delete(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long productId) {
    productService.delete(userId(jwt), productId);
    return ResponseEntity.noContent().build();
  }

  /** multipart/form-data로 전달된 이미지 파일을 제품에 추가한다. */
  @PostMapping(path = "/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<ProductResponse>> addImage(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @RequestPart("file") MultipartFile file) {
    // MultipartFile은 요청이 끝나면 사라질 수 있으므로 Service에 넘기기 전에 byte[]로 읽는다.
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException exception) {
      throw new CommonException(ErrorCode.INVALID_PRODUCT_IMAGE);
    }
    ProductResponse product =
        productService.addImage(
            userId(jwt), productId, file.getOriginalFilename(), file.getContentType(), content);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(product));
  }

  /** 이미지 ID 목록의 순서와 대표 이미지 ID를 한 번에 변경한다. */
  @PutMapping("/{productId}/images")
  public ApiResponse<ProductResponse> updateImages(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @Valid @RequestBody UpdateProductImagesRequest request) {
    return ApiResponse.success(productService.updateImages(userId(jwt), productId, request));
  }

  /** 지정한 이미지를 제품에서 삭제한다. */
  @DeleteMapping("/{productId}/images/{imageId}")
  public ApiResponse<ProductResponse> deleteImage(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @PathVariable @Positive long imageId) {
    return ApiResponse.success(productService.deleteImage(userId(jwt), productId, imageId));
  }

  /** 저장소에서 이미지 파일을 읽어 HTTP 응답 본문으로 반환한다. */
  @GetMapping("/{productId}/images/{imageId}")
  public ResponseEntity<Resource> image(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @PathVariable @Positive long imageId) {
    var image = productService.image(userId(jwt), productId, imageId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(image.contentType()))
        .body(image.resource());
  }

  /** JWT의 subject 문자열을 서비스 계층에서 사용하는 사용자 ID(long)로 변환한다. */
  private long userId(Jwt jwt) {
    try {
      return Long.parseLong(jwt.getSubject());
    } catch (NumberFormatException exception) {
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    }
  }
}
