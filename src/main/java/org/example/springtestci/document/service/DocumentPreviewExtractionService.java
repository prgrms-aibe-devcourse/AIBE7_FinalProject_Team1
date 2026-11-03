package org.example.springtestci.document.service;

import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.security.RequestRateLimiter;
import org.example.springtestci.document.ai.DocumentFactExtractionException;
import org.example.springtestci.document.ai.DocumentFactExtractor;
import org.example.springtestci.document.ai.ExtractedFactsSanitizer;
import org.example.springtestci.document.entity.DocumentType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 제품 등록 폼 자동 입력용 미리보기 추출. 제품·문서·추출 기록을 만들지 않고 정제한 후보값만 돌려준다.
 *
 * <p>입력 식별자는 로그인한 사용자 ID뿐이다. 마스킹 확인과 외부 AI 전송 동의가 모두 있어야 하며, 파일은 저장하지 않고 호출이 끝나면 버린다. 트랜잭션을 쓰지
 * 않는다(DB를 건드리지 않고 외부 AI 호출이 길어질 수 있다). 후보값은 폼에 채워 보여 주는 용도이며, 사용자가 확인한 값으로 제품이 등록될 때 비로소 저장된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentPreviewExtractionService {

  static final int MAX_REQUESTS = 10;
  static final Duration WINDOW = Duration.ofMinutes(1);

  private final DocumentFileValidator fileValidator;
  private final ObjectProvider<DocumentFactExtractor> extractor;
  private final RequestRateLimiter rateLimiter;

  /**
   * 마스킹을 확인한 사본에서 제품 사실 후보를 뽑는다.
   *
   * <p>횟수 제한은 비용이 드는 AI 호출 앞에서 먼저 센다. 사용자가 반복해서 눌러도 외부 호출이 한도를 넘지 않게 하기 위해서다.
   */
  public Map<String, Object> preview(
      Long userId,
      boolean maskingConfirmed,
      boolean consented,
      DocumentType type,
      String uploadedFilename,
      String contentType,
      byte[] content) {
    if (!maskingConfirmed) {
      throw new CommonException(ErrorCode.MASKING_NOT_CONFIRMED);
    }
    if (!consented) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    DocumentFactExtractor aiExtractor = extractor.getIfAvailable();
    if (aiExtractor == null) {
      throw new CommonException(ErrorCode.DOCUMENT_AI_UNAVAILABLE);
    }
    DocumentFileValidator.FileKind kind =
        fileValidator.validate(uploadedFilename, contentType, content);
    rateLimiter.check("document-extraction-preview", "user:" + userId, MAX_REQUESTS, WINDOW);
    try {
      return ExtractedFactsSanitizer.sanitize(
          aiExtractor.extract(content, kind.contentType(), type));
    } catch (DocumentFactExtractionException exception) {
      // 문서 내용이나 오류 원문은 남기지 않고 분류 코드만 기록한다.
      log.warn("Preview extraction failed errorCode={}", exception.errorCode());
      throw new CommonException(ErrorCode.DOCUMENT_AI_UNAVAILABLE);
    } catch (RuntimeException exception) {
      log.warn("Preview extraction failed errorCode=AI_ERROR");
      throw new CommonException(ErrorCode.DOCUMENT_AI_UNAVAILABLE);
    }
  }
}
