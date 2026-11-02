package org.example.springtestci.document.service;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.document.ai.DocumentFactExtractor;
import org.example.springtestci.document.dto.ExtractionCreateRequest;
import org.example.springtestci.document.entity.Document;
import org.example.springtestci.document.entity.DocumentExtraction;
import org.example.springtestci.document.entity.DocumentType;
import org.example.springtestci.document.entity.ExtractionStatus;
import org.example.springtestci.document.repository.DocumentExtractionRepository;
import org.example.springtestci.document.repository.DocumentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 문서의 AI 제품 정보 추출 요청·조회.
 *
 * <p>추출은 마스킹 확인이 끝난 사본을 대상으로 하고, 사용자가 외부 AI 전송에 동의했을 때만 요청할 수 있다. 문서당 유효한(대기·처리 중·추출 완료·확정) 추출은 하나만
 * 허용하며, 실패하거나 반려한 뒤에는 새 요청으로 다시 시도한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentExtractionService {

  static final String SCHEMA_VERSION = "product-facts-v1";
  static final Set<ExtractionStatus> ACTIVE =
      EnumSet.of(
          ExtractionStatus.QUEUED,
          ExtractionStatus.PROCESSING,
          ExtractionStatus.EXTRACTED,
          ExtractionStatus.CONFIRMED);

  private final DocumentRepository documentRepository;
  private final DocumentExtractionRepository extractionRepository;
  private final ProductOwnerReader productOwnerReader;
  private final ObjectProvider<DocumentFactExtractor> extractor;
  private final DocumentExtractionProcessor processor;
  private final java.time.Clock clock;

  /** 추출 요청을 접수하고(QUEUED) 커밋 뒤에 비동기로 처리를 시작한다. */
  @Transactional
  public DocumentExtraction request(Long documentId, Long userId, ExtractionCreateRequest request) {
    DocumentFactExtractor aiExtractor = extractor.getIfAvailable();
    if (aiExtractor == null) {
      throw new CommonException(ErrorCode.DOCUMENT_AI_UNAVAILABLE);
    }
    Long productId =
        documentRepository
            .findProductIdById(documentId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!productOwnerReader.lockOwnerId(productId).filter(userId::equals).isPresent()) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    Document document =
        documentRepository
            .findByIdForUpdate(documentId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    requireCurrentOwner(document, userId);
    if (document.isRedactionRequired()) {
      throw new CommonException(ErrorCode.DOCUMENT_REDACTION_REQUIRED);
    }
    if (extractionRepository.existsByDocumentIdAndStatusIn(documentId, ACTIVE)) {
      throw new CommonException(ErrorCode.DOCUMENT_EXTRACTION_ACTIVE);
    }
    DocumentExtraction extraction =
        extractionRepository.save(
            DocumentExtraction.request(
                documentId,
                document.getVersion(),
                userId,
                clock.instant(),
                request.consentNoticeVersion(),
                aiExtractor.modelName(),
                SCHEMA_VERSION));
    startAfterCommit(extraction.getId(), request.documentType());
    return extraction;
  }

  @Transactional(readOnly = true)
  public DocumentExtraction get(Long documentId, Long extractionId, Long userId) {
    requireCurrentOwner(findDocument(documentId), userId);
    return extractionRepository
        .findByIdAndDocumentId(extractionId, documentId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  @Transactional(readOnly = true)
  public DocumentExtraction latest(Long documentId, Long userId) {
    requireCurrentOwner(findDocument(documentId), userId);
    return extractionRepository
        .findFirstByDocumentIdOrderByIdDesc(documentId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private Document findDocument(Long documentId) {
    return documentRepository
        .findById(documentId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  /** 다른 사람의 문서가 존재하는지 알려 주지 않기 위해 소유자가 아니면 404로 응답한다. */
  private void requireCurrentOwner(Document document, Long userId) {
    Long ownerId = productOwnerReader.findOwnerId(document.getProductId()).orElse(null);
    if (!userId.equals(ownerId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
  }

  private void startAfterCommit(Long extractionId, DocumentType type) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatch(extractionId, type);
            }
          });
    } else {
      dispatch(extractionId, type);
    }
  }

  /** 스레드풀이 가득 차 거부되면 QUEUED로 남겨 두지 않고 즉시 실패 처리한다. */
  private void dispatch(Long extractionId, DocumentType type) {
    try {
      processor.processAsync(extractionId, type);
    } catch (RejectedExecutionException exception) {
      processor.failRejected(extractionId);
    }
  }
}
