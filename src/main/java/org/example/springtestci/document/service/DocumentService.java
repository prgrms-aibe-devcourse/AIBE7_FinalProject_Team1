package org.example.springtestci.document.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.storage.FileStorage;
import org.example.springtestci.document.dto.FileUrlResponse;
import org.example.springtestci.document.entity.Document;
import org.example.springtestci.document.entity.DocumentExtraction;
import org.example.springtestci.document.entity.ExtractionStatus;
import org.example.springtestci.document.repository.DocumentExtractionRepository;
import org.example.springtestci.document.repository.DocumentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 제품 증빙 사본의 업로드·조회·삭제.
 *
 * <p>개인정보가 있는 원본은 브라우저에서만 처리하고, 서버는 마스킹 확인이 끝난 사본만 받는다. 열람과 삭제 권한은 업로더가 아니라 제품의 현재 소유자를 기준으로 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

  static final int MAX_DOCUMENTS_PER_PRODUCT = 20;
  static final Duration FILE_URL_TTL = Duration.ofMinutes(5);

  private final DocumentRepository documentRepository;
  private final DocumentExtractionRepository extractionRepository;
  private final ObjectProvider<FileStorage> fileStorage;
  private final DocumentFileValidator fileValidator;
  private final ProductOwnerReader productOwnerReader;
  private final Clock clock;

  /** 현재 소유자가 마스킹을 확인한 사본을 올린다. 업로드 파일명은 확장자 판별에만 쓰고 저장하지 않는다. */
  @Transactional
  public Document upload(
      Long productId,
      Long userId,
      boolean maskingConfirmed,
      String uploadedFilename,
      String contentType,
      byte[] content) {
    if (!maskingConfirmed) {
      throw new CommonException(ErrorCode.MASKING_NOT_CONFIRMED);
    }
    requireCurrentOwner(productId, userId);
    DocumentFileValidator.FileKind kind =
        fileValidator.validate(uploadedFilename, contentType, content);

    if (documentRepository.countByProductId(productId) >= MAX_DOCUMENTS_PER_PRODUCT) {
      throw new CommonException(ErrorCode.DOCUMENT_LIMIT_EXCEEDED);
    }

    String objectKey = store(productId, kind.extension(), content);
    try {
      return documentRepository.save(
          Document.register(
              productId, userId, objectKey, kind.contentType(), content.length, clock.instant()));
    } catch (RuntimeException exception) {
      deleteStoredFile(objectKey);
      throw exception;
    }
  }

  /**
   * 재마스킹한 사본으로 파일을 교체한다. 문서 ID와 이력 연결은 유지하고, 이전 파일은 커밋 뒤에 지운다. 이전 사본을 분석한 AI 후보값은 더 이상 유효하지 않으므로 검토
   * 대기 중인 추출은 반려 처리한다.
   */
  @Transactional
  public Document replace(
      Long documentId,
      Long userId,
      boolean maskingConfirmed,
      String uploadedFilename,
      String contentType,
      byte[] content) {
    if (!maskingConfirmed) {
      throw new CommonException(ErrorCode.MASKING_NOT_CONFIRMED);
    }
    // 같은 문서를 동시에 교체하거나 추출하지 못하도록 행을 잠근다.
    Document document =
        documentRepository
            .findByIdForUpdate(documentId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    Long ownerId = productOwnerReader.findOwnerId(document.getProductId()).orElse(null);
    if (!userId.equals(ownerId)) {
      // 다른 사람의 문서가 존재하는지 알려 주지 않는다.
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    DocumentFileValidator.FileKind kind =
        fileValidator.validate(uploadedFilename, contentType, content);

    String oldObjectKey = document.getObjectKey();
    String newObjectKey = store(document.getProductId(), kind.extension(), content);
    try {
      document.replaceFile(newObjectKey, kind.contentType(), content.length, clock.instant());
      documentRepository.save(document);
      rejectPendingExtractions(documentId);
    } catch (RuntimeException exception) {
      deleteStoredFile(newObjectKey);
      throw exception;
    }
    deleteStoredFileAfterCommit(oldObjectKey);
    return document;
  }

  private void rejectPendingExtractions(Long documentId) {
    for (DocumentExtraction extraction :
        extractionRepository.findByDocumentIdOrderByIdDesc(documentId)) {
      if (extraction.getStatus() == ExtractionStatus.EXTRACTED) {
        extraction.reject();
      }
    }
  }

  /** 현재 소유자만 조회한다. 소유권이 이전되면 이전 소유자의 요청은 거부된다. */
  @Transactional(readOnly = true)
  public List<Document> list(Long productId, Long userId) {
    requireCurrentOwner(productId, userId);
    return documentRepository.findByProductIdOrderByIdDesc(productId);
  }

  /** 재마스킹이 필요한 사본(개인정보 누락 발견)은 교체되기 전까지 열람할 수 없다. */
  @Transactional(readOnly = true)
  public FileUrlResponse createFileUrl(Long documentId, Long userId) {
    Document document = findDocumentOfCurrentOwner(documentId, userId);
    if (document.isRedactionRequired()) {
      throw new CommonException(ErrorCode.DOCUMENT_REDACTION_REQUIRED);
    }
    Instant expiresAt = clock.instant().plus(FILE_URL_TTL);
    return new FileUrlResponse(
        storage().createTemporaryUrl(document.getObjectKey(), expiresAt), expiresAt);
  }

  /**
   * 임시 URL로 실제 다운로드하는 시점에 요청자가 그 파일이 속한 제품의 현재 소유자인지 다시 확인한다. URL 발급 뒤 소유권이 이전되거나 파일이 교체된 경우 거부된다.
   * 존재 여부를 알려 주지 않도록 모두 RESOURCE_NOT_FOUND로 응답한다.
   */
  @Transactional(readOnly = true)
  public void requireCurrentOwnerOfFile(String objectKey, Long userId) {
    Document document =
        documentRepository
            .findByObjectKey(objectKey)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    Long ownerId = productOwnerReader.findOwnerId(document.getProductId()).orElse(null);
    if (!userId.equals(ownerId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  /**
   * 현재 소유자가 본인이 올렸고 이력에 연결된 적이 없는(보호되지 않은) 사본만 삭제한다. 이전받은 사본과 이력에 연결된 사본은 삭제할 수 없다. 문서에 딸린 AI 추출
   * 기록도 함께 지운다.
   */
  @Transactional
  public void delete(Long documentId, Long userId) {
    Document document = findDocumentOfCurrentOwner(documentId, userId);
    if (!document.isUploadedBy(userId) || document.isProtected()) {
      throw new CommonException(ErrorCode.DOCUMENT_DELETE_NOT_ALLOWED);
    }
    extractionRepository.deleteByDocumentId(document.getId());
    documentRepository.delete(document);
    deleteStoredFileAfterCommit(document.getObjectKey());
  }

  private Document findDocumentOfCurrentOwner(Long documentId, Long userId) {
    Document document =
        documentRepository
            .findById(documentId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    Long ownerId = productOwnerReader.findOwnerId(document.getProductId()).orElse(null);
    if (!userId.equals(ownerId)) {
      // 다른 사람의 문서가 존재하는지 알려 주지 않는다.
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return document;
  }

  private void requireCurrentOwner(Long productId, Long userId) {
    Long ownerId =
        productOwnerReader
            .findOwnerId(productId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!ownerId.equals(userId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
  }

  /** 저장소 구현이 없는 환경(운영 프로필 등)에서도 앱은 기동하고, 파일 기능만 503으로 응답한다. */
  private FileStorage storage() {
    FileStorage storage = fileStorage.getIfAvailable();
    if (storage == null) {
      throw new CommonException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE);
    }
    return storage;
  }

  private String store(Long productId, String extension, byte[] content) {
    try {
      return storage()
          .store("documents/" + productId, extension, new ByteArrayInputStream(content));
    } catch (IOException exception) {
      log.error("Failed to store document file productId={}", productId);
      throw new CommonException(ErrorCode.INTERNAL_ERROR);
    }
  }

  private void deleteStoredFileAfterCommit(String objectKey) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              deleteStoredFile(objectKey);
            }
          });
    } else {
      deleteStoredFile(objectKey);
    }
  }

  private void deleteStoredFile(String objectKey) {
    try {
      storage().delete(objectKey);
    } catch (IOException | RuntimeException exception) {
      log.warn("Failed to delete stored document file");
    }
  }
}
