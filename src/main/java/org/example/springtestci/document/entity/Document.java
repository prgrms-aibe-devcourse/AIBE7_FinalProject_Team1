package org.example.springtestci.document.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 마스킹 확인이 끝난 제품 증빙 사본. 개인정보가 포함된 원본은 서버로 전달되지 않는다.
 *
 * <p>ERD의 documents 컬럼만 가진다. 문서 유형·표시명은 저장하지 않는다.
 */
@Entity
@Table(name = "documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private Long productId;

  /** 처음 업로드한 회원. 소유권이 이전되어도 바꾸지 않는다. */
  @Column(nullable = false, updatable = false)
  private Long uploaderId;

  @Column(nullable = false, length = 500)
  private String objectKey;

  @Column(nullable = false, length = 100)
  private String contentType;

  @Column(nullable = false)
  private long byteSize;

  /** 재마스킹으로 사본을 교체할 때마다 증가한다. 오래된 사본을 분석한 늦은 AI 결과를 구분하는 기준. */
  @Column(nullable = false)
  private int version;

  @Column(nullable = false)
  private Instant maskConfirmedAt;

  /** 저장된 사본에서 개인정보 누락이 발견된 시각. 값이 있으면 열람·AI 전송을 막고, 교체 성공 때만 해제한다. */
  private Instant redactionRequiredAt;

  /** 처음 이력에 연결되거나 소유권 이전으로 삭제 제한이 생긴 시각. 한 번 설정하면 유지한다. */
  private Instant protectedAt;

  /** 재마스킹본으로 교체한 횟수와 마지막 교체 시각. 상세 기록은 document_replacements에 남긴다. */
  @Column(nullable = false)
  private int replacementCount;

  private Instant lastReplacedAt;

  private Document(
      Long productId,
      Long uploaderId,
      String objectKey,
      String contentType,
      long byteSize,
      Instant maskConfirmedAt) {
    this.productId = productId;
    this.uploaderId = uploaderId;
    this.objectKey = objectKey;
    this.contentType = contentType;
    this.byteSize = byteSize;
    this.version = 1;
    this.maskConfirmedAt = maskConfirmedAt;
  }

  public static Document register(
      Long productId,
      Long uploaderId,
      String objectKey,
      String contentType,
      long byteSize,
      Instant maskConfirmedAt) {
    return new Document(productId, uploaderId, objectKey, contentType, byteSize, maskConfirmedAt);
  }

  public boolean isUploadedBy(Long userId) {
    return uploaderId.equals(userId);
  }

  public boolean isProtected() {
    return protectedAt != null;
  }

  public boolean isRedactionRequired() {
    return redactionRequiredAt != null;
  }

  public void protect(Instant now) {
    if (protectedAt == null) {
      protectedAt = now;
    }
  }

  public void requireRedaction(Instant now) {
    if (redactionRequiredAt == null) {
      redactionRequiredAt = now;
    }
  }

  /** 재마스킹한 사본으로 교체한다. 문서 ID와 이력 연결은 유지하고, 교체에 성공한 때만 차단을 해제한다. */
  public void replaceFile(
      String newObjectKey, String newContentType, long newByteSize, Instant now) {
    this.objectKey = newObjectKey;
    this.contentType = newContentType;
    this.byteSize = newByteSize;
    this.version += 1;
    this.replacementCount += 1;
    this.lastReplacedAt = now;
    this.maskConfirmedAt = now;
    this.redactionRequiredAt = null;
  }
}
