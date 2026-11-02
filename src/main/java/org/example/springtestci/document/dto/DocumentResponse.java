package org.example.springtestci.document.dto;

import java.time.Instant;
import org.example.springtestci.document.entity.Document;

/** 저장소 키와 업로더 등 내부 값은 응답에 포함하지 않는다. */
public record DocumentResponse(
    Long id,
    Long productId,
    String contentType,
    long byteSize,
    int version,
    Instant maskConfirmedAt,
    boolean redactionRequired,
    boolean deletable,
    int replacementCount,
    Instant lastReplacedAt) {

  /** {@code currentUserId}가 이 문서를 직접 삭제할 수 있는지는 업로더이고 보호되지 않았을 때만 참이다. */
  public static DocumentResponse from(Document document, Long currentUserId) {
    return new DocumentResponse(
        document.getId(),
        document.getProductId(),
        document.getContentType(),
        document.getByteSize(),
        document.getVersion(),
        document.getMaskConfirmedAt(),
        document.isRedactionRequired(),
        document.isUploadedBy(currentUserId) && !document.isProtected(),
        document.getReplacementCount(),
        document.getLastReplacedAt());
  }
}
