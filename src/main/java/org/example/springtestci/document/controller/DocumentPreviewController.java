package org.example.springtestci.document.controller;

import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.document.entity.DocumentType;
import org.example.springtestci.document.service.DocumentPreviewExtractionService;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 제품 등록 폼 자동 입력용 미리보기 추출 API. 저장 없이 후보값만 돌려주므로 제품이나 문서 번호가 필요 없고, 로그인한 사용자만 호출할 수 있다. */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentPreviewController {

  private final DocumentPreviewExtractionService previewService;

  /** 마스킹을 확인한 사본과 외부 AI 전송 동의를 받아 정제된 후보값을 응답한다. 아무것도 저장하지 않는다. */
  @PostMapping(path = "/extraction-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  ApiResponse<Map<String, Object>> preview(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam boolean maskingConfirmed,
      @RequestParam boolean consented,
      @RequestParam DocumentType documentType,
      @RequestPart("file") MultipartFile file) {
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException exception) {
      throw new CommonException(ErrorCode.INVALID_DOCUMENT_FILE);
    }
    return ApiResponse.success(
        previewService.preview(
            userId(jwt),
            maskingConfirmed,
            consented,
            documentType,
            file.getOriginalFilename(),
            file.getContentType(),
            content));
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
