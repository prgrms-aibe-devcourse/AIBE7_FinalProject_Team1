package org.example.springtestci.document.service;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.document.dto.ExtractedFields;
import org.example.springtestci.document.entity.Document;
import org.example.springtestci.document.entity.DocumentExtraction;
import org.example.springtestci.document.entity.ExtractionStatus;
import org.example.springtestci.document.repository.DocumentExtractionRepository;
import org.example.springtestci.document.repository.DocumentRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추출 결과의 검토: 사용자가 값을 확인·수정해 확정하거나 반려한다.
 *
 * <p>확정에는 AI 후보값이 아니라 사용자가 검토한 값만 사용한다. 같은 추출에 대한 확정·반려가 동시에 들어와도 행을 잠가 한 번만 처리한다.
 */
@Service
@RequiredArgsConstructor
public class DocumentExtractionReviewService {

  private final DocumentRepository documentRepository;
  private final DocumentExtractionRepository extractionRepository;
  private final ProductOwnerReader productOwnerReader;
  private final ObjectProvider<ProductEventWriter> eventWriter;
  private final Clock clock;

  /** 검토한 값으로 이력을 만들고 문서를 보호(삭제 불가) 상태로 바꾼다. */
  @Transactional
  public DocumentExtraction confirm(
      Long documentId, Long extractionId, Long userId, ExtractedFields reviewed) {
    // 이력 구현체가 아직 없는 환경에서는 503으로 응답한다.
    ProductEventWriter writer = eventWriter.getIfAvailable();
    if (writer == null) {
      throw new CommonException(ErrorCode.DOCUMENT_HISTORY_UNAVAILABLE);
    }
    // 직접 이력 등록(HistoryService.create)은 제품 → 증빙 문서 순으로 잠근다. 같은 순서로 잠그지 않으면 같은 제품·문서를
    // 동시에 처리할 때 서로 기다리다 한쪽이 교착으로 중단된다.
    Document document = findOwnedDocumentLockingProductFirst(documentId, userId);
    DocumentExtraction extraction = findForUpdate(documentId, extractionId);
    if (extraction.getDocumentVersion() != document.getVersion()) {
      // 추출 이후 사본이 교체되었다면 이전 사본 기준의 결과는 확정할 수 없다.
      throw new CommonException(ErrorCode.INVALID_EXTRACTION_STATE);
    }
    // 모든 항목이 비어 있으면 남길 이력이 없으므로 거부한다.
    if (isEmpty(reviewed)) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    // 상태 검사를 이력 생성보다 먼저 한다. 이미 확정·반려된 건에 이력이 중복 생성되지 않는다.
    if (extraction.getStatus() != ExtractionStatus.EXTRACTED) {
      throw new CommonException(ErrorCode.INVALID_EXTRACTION_STATE);
    }
    Long eventId = writer.recordFromDocument(document.getProductId(), documentId, userId, reviewed);
    // 확정: 후보값을 비우고 이력 ID를 기록한 뒤, 이력에 연결된 문서는 삭제되지 않게 보호한다.
    extraction.confirm(eventId, clock.instant());
    document.protect(clock.instant());
    return extraction;
  }

  /** 추출 결과를 버린다. 후보값은 비우고, 같은 문서에 다시 추출을 요청할 수 있게 된다. */
  @Transactional
  public DocumentExtraction reject(Long documentId, Long extractionId, Long userId) {
    findOwnedDocumentLockingProductFirst(documentId, userId);
    DocumentExtraction extraction = findForUpdate(documentId, extractionId);
    extraction.reject();
    return extraction;
  }

  private DocumentExtraction findForUpdate(Long documentId, Long extractionId) {
    return extractionRepository
        .findByIdAndDocumentIdForUpdate(extractionId, documentId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  /** 제품 행을 먼저 잠근 뒤 문서 행을 잠근다. 잠금 순서는 확정에서 이력 저장까지 이어지는 흐름과 같다. */
  private Document findOwnedDocumentLockingProductFirst(Long documentId, Long userId) {
    // 엔티티를 먼저 로딩하면 잠금 대기 중 교체된 이전 버전이 영속성 컨텍스트에 남으므로 ID만 읽는다.
    Long productId =
        documentRepository
            .findProductIdById(documentId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    Long ownerId = productOwnerReader.lockOwnerId(productId).orElse(null);
    if (!userId.equals(ownerId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return documentRepository
        .findByIdForUpdate(documentId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private static boolean isEmpty(ExtractedFields f) {
    return f.productName() == null
        && f.modelName() == null
        && f.purchaseDate() == null
        && f.vendor() == null
        && f.price() == null
        && f.warrantyMonths() == null
        && f.repairDate() == null
        && f.repairDescription() == null;
  }
}
