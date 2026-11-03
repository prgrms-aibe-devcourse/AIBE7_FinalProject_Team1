package org.example.springtestci.document.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.security.RequestRateLimiter;
import org.example.springtestci.document.ai.DocumentFactExtractionException;
import org.example.springtestci.document.ai.DocumentFactExtractor;
import org.example.springtestci.document.entity.DocumentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DocumentPreviewExtractionServiceTest {

  private static final Long USER = 1L;
  private static final byte[] PNG =
      new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01};

  @Mock private ObjectProvider<DocumentFactExtractor> extractorProvider;
  @Mock private DocumentFactExtractor extractor;
  @Mock private RequestRateLimiter rateLimiter;

  private DocumentPreviewExtractionService service;

  @BeforeEach
  void setUp() {
    lenient().when(extractorProvider.getIfAvailable()).thenReturn(extractor);
    service =
        new DocumentPreviewExtractionService(
            new DocumentFileValidator(), extractorProvider, rateLimiter);
  }

  private Map<String, Object> run(boolean masked, boolean consented) {
    return service.preview(
        USER, masked, consented, DocumentType.RECEIPT, "masked.png", "image/png", PNG);
  }

  private ErrorCode errorOf(Runnable action) {
    return assertThrows(CommonException.class, action::run).errorCode();
  }

  @Test
  void 정제된_후보값만_돌려준다() throws Exception {
    when(extractor.extract(any(), any(), any()))
        .thenReturn(
            Map.of(
                "modelName", "NT950XED",
                "purchaseDate", "2026-01-05",
                "ownerPhone", "010-1234-5678"));

    Map<String, Object> facts = run(true, true);

    assertEquals("NT950XED", facts.get("modelName"));
    assertEquals("2026-01-05", facts.get("purchaseDate"));
    assertNull(facts.get("ownerPhone"));
    assertNull(facts.get("price"));
    verify(rateLimiter)
        .check(
            "document-extraction-preview",
            "user:1",
            DocumentPreviewExtractionService.MAX_REQUESTS,
            DocumentPreviewExtractionService.WINDOW);
  }

  @Test
  void 마스킹_확인이_없으면_AI를_호출하지_않는다() throws Exception {
    assertEquals(ErrorCode.MASKING_NOT_CONFIRMED, errorOf(() -> run(false, true)));
    verify(extractor, never()).extract(any(), any(), any());
  }

  @Test
  void 외부_AI_전송_동의가_없으면_거부한다() throws Exception {
    assertEquals(ErrorCode.INVALID_INPUT, errorOf(() -> run(true, false)));
    verify(extractor, never()).extract(any(), any(), any());
  }

  @Test
  void AI_구현체가_없으면_사용할_수_없다고_응답한다() {
    when(extractorProvider.getIfAvailable()).thenReturn(null);

    assertEquals(ErrorCode.DOCUMENT_AI_UNAVAILABLE, errorOf(() -> run(true, true)));
  }

  @Test
  void 파일_형식이_올바르지_않으면_거부한다() throws Exception {
    ErrorCode code =
        errorOf(
            () ->
                service.preview(
                    USER,
                    true,
                    true,
                    DocumentType.RECEIPT,
                    "masked.png",
                    "image/png",
                    new byte[] {1, 2, 3}));

    assertEquals(ErrorCode.INVALID_DOCUMENT_FILE, code);
    verify(extractor, never()).extract(any(), any(), any());
  }

  @Test
  void AI_호출이_실패하면_사용할_수_없다고_응답한다() throws Exception {
    when(extractor.extract(any(), any(), any()))
        .thenThrow(new DocumentFactExtractionException("AI_TIMEOUT"));

    assertEquals(ErrorCode.DOCUMENT_AI_UNAVAILABLE, errorOf(() -> run(true, true)));
  }
}
