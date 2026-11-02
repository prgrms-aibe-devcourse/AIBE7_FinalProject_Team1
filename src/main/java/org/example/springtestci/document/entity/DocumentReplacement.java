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
 * 증빙 사본을 재마스킹본으로 교체한 기록. 한 번 남기면 수정하지 않는다.
 *
 * <p>교체해도 문서 번호와 이력 연결은 그대로라서, 이력이 확정된 뒤 다른 파일로 바뀐 사실을 확인하는 근거가 된다. 파일 내용은 저장하지 않고 종류·크기·SHA-256만
 * 남긴다.
 */
@Entity
@Table(name = "document_replacements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentReplacement {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private Long documentId;

  @Column(nullable = false, updatable = false)
  private int fromVersion;

  @Column(nullable = false, updatable = false)
  private int toVersion;

  @Column(nullable = false, updatable = false)
  private Long replacedById;

  /** 교체 당시 이력에 연결되어 보호 중이었는지. 확정된 증빙이 바뀐 경우를 구분한다. */
  @Column(nullable = false, updatable = false)
  private boolean wasProtected;

  @Column(nullable = false, updatable = false, length = 100)
  private String oldContentType;

  @Column(nullable = false, updatable = false, length = 100)
  private String newContentType;

  @Column(nullable = false, updatable = false)
  private long oldByteSize;

  @Column(nullable = false, updatable = false)
  private long newByteSize;

  @Column(nullable = false, updatable = false, length = 64)
  private String newSha256;

  @Column(nullable = false, updatable = false)
  private Instant replacedAt;

  private DocumentReplacement(
      Long documentId,
      int fromVersion,
      int toVersion,
      Long replacedById,
      boolean wasProtected,
      String oldContentType,
      String newContentType,
      long oldByteSize,
      long newByteSize,
      String newSha256,
      Instant replacedAt) {
    this.documentId = documentId;
    this.fromVersion = fromVersion;
    this.toVersion = toVersion;
    this.replacedById = replacedById;
    this.wasProtected = wasProtected;
    this.oldContentType = oldContentType;
    this.newContentType = newContentType;
    this.oldByteSize = oldByteSize;
    this.newByteSize = newByteSize;
    this.newSha256 = newSha256;
    this.replacedAt = replacedAt;
  }

  public static DocumentReplacement record(
      Long documentId,
      int fromVersion,
      int toVersion,
      Long replacedById,
      boolean wasProtected,
      String oldContentType,
      String newContentType,
      long oldByteSize,
      long newByteSize,
      String newSha256,
      Instant replacedAt) {
    return new DocumentReplacement(
        documentId,
        fromVersion,
        toVersion,
        replacedById,
        wasProtected,
        oldContentType,
        newContentType,
        oldByteSize,
        newByteSize,
        newSha256,
        replacedAt);
  }
}
