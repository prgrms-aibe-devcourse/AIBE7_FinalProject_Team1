package org.example.springtestci.document.controller;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.document.dto.DocumentResponse;
import org.example.springtestci.document.dto.FileUrlResponse;
import org.example.springtestci.document.dto.UpdateDocumentNameRequest;
import org.example.springtestci.document.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DocumentController {

  private final DocumentService documentService;

  @PostMapping(
      path = "/products/{productId}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ResponseEntity<ApiResponse<DocumentResponse>> upload(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long productId,
      @RequestParam boolean maskingConfirmed,
      @RequestParam(required = false) String name,
      @RequestPart("file") MultipartFile file) {
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException exception) {
      throw new CommonException(ErrorCode.INVALID_DOCUMENT_FILE);
    }
    Long userId = userId(jwt);
    var document =
        documentService.upload(
            productId,
            userId,
            maskingConfirmed,
            file.getOriginalFilename(),
            file.getContentType(),
            content,
            name);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success(DocumentResponse.from(document, userId)));
  }

  /** 재마스킹한 사본으로 같은 문서의 파일을 교체한다. 문서 ID와 이력 연결은 유지된다. */
  @PutMapping(path = "/documents/{documentId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ApiResponse<DocumentResponse> replace(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long documentId,
      @RequestParam boolean maskingConfirmed,
      @RequestPart("file") MultipartFile file) {
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException exception) {
      throw new CommonException(ErrorCode.INVALID_DOCUMENT_FILE);
    }
    Long userId = userId(jwt);
    var document =
        documentService.replace(
            documentId,
            userId,
            maskingConfirmed,
            file.getOriginalFilename(),
            file.getContentType(),
            content);
    return ApiResponse.success(DocumentResponse.from(document, userId));
  }

  /** 현재 소유자가 문서 ID로 지정한 사본의 표시 이름을 변경한다. */
  @PatchMapping("/documents/{documentId}/name")
  ApiResponse<DocumentResponse> rename(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long documentId,
      @Valid @RequestBody UpdateDocumentNameRequest request) {
    Long userId = userId(jwt);
    return ApiResponse.success(
        DocumentResponse.from(documentService.rename(documentId, userId, request.name()), userId));
  }

  @GetMapping("/products/{productId}/documents")
  ApiResponse<List<DocumentResponse>> list(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long productId) {
    Long userId = userId(jwt);
    return ApiResponse.success(
        documentService.list(productId, userId).stream()
            .map(document -> DocumentResponse.from(document, userId))
            .toList());
  }

  @GetMapping("/documents/{documentId}/file-url")
  ResponseEntity<ApiResponse<FileUrlResponse>> fileUrl(
      @AuthenticationPrincipal Jwt jwt, @PathVariable Long documentId) {
    return ResponseEntity.ok()
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .body(ApiResponse.success(documentService.createFileUrl(documentId, userId(jwt))));
  }

  @DeleteMapping("/documents/{documentId}")
  ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long documentId) {
    documentService.delete(documentId, userId(jwt));
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/documents/{documentId}/file")
  ResponseEntity<byte[]> download(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable Long documentId,
      @RequestParam int version,
      @RequestParam long expires,
      @RequestParam String sig) {
    var file = documentService.download(documentId, userId(jwt), version, expires, sig);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.contentType()))
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("Referrer-Policy", "no-referrer")
        .header("Content-Disposition", "inline; filename=\"document\"")
        .body(file.bytes());
  }

  /** JWT subject를 사용자 ID로 사용한다. 인증 도메인이 확정되면 공통 컴포넌트로 옮긴다. */
  private Long userId(Jwt jwt) {
    try {
      return Long.valueOf(jwt.getSubject());
    } catch (NumberFormatException exception) {
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    }
  }
}
