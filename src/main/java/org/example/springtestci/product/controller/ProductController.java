package org.example.springtestci.product.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import java.util.List;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.product.dto.CreateProductRequest;
import org.example.springtestci.product.dto.ProductResponse;
import org.example.springtestci.product.dto.UpdateProductImagesRequest;
import org.example.springtestci.product.dto.UpdateProductRequest;
import org.example.springtestci.product.service.ProductService;
import org.springframework.core.io.Resource;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/products")
/** 인증된 사용자의 제품과 제품 이미지를 관리하는 HTTP API를 제공한다. */
public class ProductController {

  private final ProductService productService;

  /** 제품 유스케이스를 처리할 서비스로 컨트롤러를 구성한다. */
  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  /** 인증된 사용자의 새 제품을 등록한다. */
  @PostMapping
  public ResponseEntity<ApiResponse<ProductResponse>> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateProductRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(productService.create(userId(jwt), request)));
  }

  /** 인증된 사용자가 소유한 제품 목록을 조회한다. */
  @GetMapping
  public ApiResponse<List<ProductResponse>> listMine(@AuthenticationPrincipal Jwt jwt) {
    return ApiResponse.success(productService.listMine(userId(jwt)));
  }

  /** 인증된 사용자가 소유한 단일 제품을 조회한다. */
  @GetMapping("/{productId}")
  public ApiResponse<ProductResponse> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long productId) {
    return ApiResponse.success(productService.get(userId(jwt), productId));
  }

  /** 소유 제품의 수정 가능한 기본 정보를 갱신한다. */
  @PatchMapping("/{productId}")
  public ApiResponse<ProductResponse> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @Valid @RequestBody UpdateProductRequest request) {
    return ApiResponse.success(productService.update(userId(jwt), productId, request));
  }

  /** 소유 제품에 검증된 이미지 한 장을 추가한다. */
  @PostMapping(path = "/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<ProductResponse>> addImage(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @RequestPart("file") MultipartFile file) {
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

  /** 소유 제품 이미지의 전체 순서와 대표 이미지를 변경한다. */
  @PutMapping("/{productId}/images")
  public ApiResponse<ProductResponse> updateImages(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @Valid @RequestBody UpdateProductImagesRequest request) {
    return ApiResponse.success(productService.updateImages(userId(jwt), productId, request));
  }

  /** 소유 제품에서 지정한 이미지를 삭제한다. */
  @DeleteMapping("/{productId}/images/{imageId}")
  public ApiResponse<ProductResponse> deleteImage(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @PathVariable @Positive long imageId) {
    return ApiResponse.success(productService.deleteImage(userId(jwt), productId, imageId));
  }

  /** 소유권을 확인한 뒤 제품 이미지 리소스를 반환한다. */
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

  /** JWT subject를 제품 서비스가 사용하는 사용자 ID로 변환한다. */
  private long userId(Jwt jwt) {
    try {
      return Long.parseLong(jwt.getSubject());
    } catch (NumberFormatException exception) {
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    }
  }
}
