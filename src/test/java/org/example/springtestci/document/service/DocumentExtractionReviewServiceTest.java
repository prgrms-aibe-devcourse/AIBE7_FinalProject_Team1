package org.example.springtestci.document.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.document.dto.ExtractedFields;
import org.example.springtestci.document.entity.Document;
import org.example.springtestci.document.entity.DocumentExtraction;
import org.example.springtestci.document.entity.ExtractionStatus;
import org.example.springtestci.document.repository.DocumentExtractionRepository;
import org.example.springtestci.document.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DocumentExtractionReviewServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
  private static final Long OWNER = 1L;
  private static final Long OTHER = 2L;
  private static final Long PRODUCT = 100L;
  private static final Long DOCUMENT = 10L;
  private static final Long EXTRACTION = 50L;
  private static final Long EVENT = 900L;

  @Mock private DocumentRepository documentRepository;
  @Mock private DocumentExtractionRepository extractionRepository;
  @Mock private ProductOwnerReader productOwnerReader;
  @Mock private ObjectProvider<ProductEventWriter> writerProvider;
  @Mock private ProductEventWriter writer;

  private DocumentExtractionReviewService service;

  @BeforeEach
  void setUp() {
    lenient().when(writerProvider.getIfAvailable()).thenReturn(writer);
    lenient().when(productOwnerReader.findOwnerId(PRODUCT)).thenReturn(Optional.of(OWNER));
    lenient()
        .when(productOwnerReader.lockOwnerId(PRODUCT))
        .thenAnswer(invocation -> productOwnerReader.findOwnerId(PRODUCT));
    lenient().when(documentRepository.findProductIdById(DOCUMENT)).thenReturn(Optional.of(PRODUCT));
    service =
        new DocumentExtractionReviewService(
            documentRepository,
            extractionRepository,
            productOwnerReader,
            writerProvider,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Document document() {
    return Document.register(PRODUCT, OWNER, "documents/100/k.png", "image/png", 10, NOW);
  }

  private DocumentExtraction extracted() {
    DocumentExtraction extraction =
        DocumentExtraction.request(DOCUMENT, 1, OWNER, NOW, "v1", "test-model", "product-facts-v1");
    extraction.startProcessing();
    extraction.completeExtraction(Map.of("productName", "AI가 읽은 값"));
    return extraction;
  }

  private ExtractedFields reviewed() {
    return new ExtractedFields(
        "그램 17", "17Z90S", LocalDate.of(2026, 1, 5), "전자랜드", 1_500_000L, 24, null, null);
  }

  private void givenExtraction(DocumentExtraction extraction) {
    when(documentRepository.findByIdForUpdate(DOCUMENT)).thenReturn(Optional.of(document()));
    when(extractionRepository.findByIdAndDocumentIdForUpdate(EXTRACTION, DOCUMENT))
        .thenReturn(Optional.of(extraction));
  }

  private ErrorCode errorOf(Runnable action) {
    return assertThrows(CommonException.class, action::run).errorCode();
  }

  @Test
  void confirmCreatesEventFromReviewedValuesAndProtectsDocument() {
    DocumentExtraction extraction = extracted();
    Document document = document();
    when(documentRepository.findByIdForUpdate(DOCUMENT)).thenReturn(Optional.of(document));
    when(extractionRepository.findByIdAndDocumentIdForUpdate(EXTRACTION, DOCUMENT))
        .thenReturn(Optional.of(extraction));
    ExtractedFields reviewed = reviewed();
    when(writer.recordFromDocument(PRODUCT, DOCUMENT, OWNER, reviewed)).thenReturn(EVENT);

    DocumentExtraction result = service.confirm(DOCUMENT, EXTRACTION, OWNER, reviewed);

    assertEquals(ExtractionStatus.CONFIRMED, result.getStatus());
    assertEquals(EVENT, result.getResultEventId());
    assertEquals(NOW, result.getFactsConfirmedAt());
    assertNull(result.getExtractedFacts());
    assertTrue(document.isProtected());
  }

  @Test
  void confirmLocksProductBeforeDocumentToMatchHistoryRegistration() {
    DocumentExtraction extraction = extracted();
    givenExtraction(extraction);
    ExtractedFields reviewed = reviewed();
    when(writer.recordFromDocument(PRODUCT, DOCUMENT, OWNER, reviewed)).thenReturn(EVENT);

    service.confirm(DOCUMENT, EXTRACTION, OWNER, reviewed);

    // 직접 이력 등록과 같은 제품 → 문서 순서여야 동시에 처리해도 교착이 생기지 않는다.
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(productOwnerReader, documentRepository);
    order.verify(productOwnerReader).lockOwnerId(PRODUCT);
    order.verify(documentRepository).findByIdForUpdate(DOCUMENT);
  }

  @Test
  void confirmRejectsAlreadyConfirmedWithoutCreatingAnotherEvent() {
    DocumentExtraction extraction = extracted();
    extraction.confirm(EVENT, NOW);
    givenExtraction(extraction);

    assertEquals(
        ErrorCode.INVALID_EXTRACTION_STATE,
        errorOf(() -> service.confirm(DOCUMENT, EXTRACTION, OWNER, reviewed())));
    verify(writer, never()).recordFromDocument(any(), any(), any(), any());
  }

  @Test
  void confirmRejectsExtractionOfReplacedDocument() {
    DocumentExtraction extraction = extracted();
    Document replaced = document();
    replaced.replaceFile("documents/100/new.png", "image/png", 10, NOW);
    when(documentRepository.findByIdForUpdate(DOCUMENT)).thenReturn(Optional.of(replaced));
    when(extractionRepository.findByIdAndDocumentIdForUpdate(EXTRACTION, DOCUMENT))
        .thenReturn(Optional.of(extraction));

    assertEquals(
        ErrorCode.INVALID_EXTRACTION_STATE,
        errorOf(() -> service.confirm(DOCUMENT, EXTRACTION, OWNER, reviewed())));
    verify(writer, never()).recordFromDocument(any(), any(), any(), any());
  }

  @Test
  void confirmRejectsEmptyReviewedValues() {
    givenExtraction(extracted());
    ExtractedFields empty = new ExtractedFields(null, null, null, null, null, null, null, null);

    assertEquals(
        ErrorCode.INVALID_INPUT,
        errorOf(() -> service.confirm(DOCUMENT, EXTRACTION, OWNER, empty)));
    verify(writer, never()).recordFromDocument(any(), any(), any(), any());
  }

  @Test
  void confirmFailsWhenHistoryIsNotAvailable() {
    when(writerProvider.getIfAvailable()).thenReturn(null);

    assertEquals(
        ErrorCode.DOCUMENT_HISTORY_UNAVAILABLE,
        errorOf(() -> service.confirm(DOCUMENT, EXTRACTION, OWNER, reviewed())));
  }

  @Test
  void nonOwnerSeesNotFound() {

    assertEquals(
        ErrorCode.RESOURCE_NOT_FOUND,
        errorOf(() -> service.confirm(DOCUMENT, EXTRACTION, OTHER, reviewed())));
    assertEquals(
        ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> service.reject(DOCUMENT, EXTRACTION, OTHER)));
    verify(writer, never()).recordFromDocument(any(), any(), any(), any());
  }

  @Test
  void rejectClearsCandidateValues() {
    DocumentExtraction extraction = extracted();
    givenExtraction(extraction);

    DocumentExtraction result = service.reject(DOCUMENT, EXTRACTION, OWNER);

    assertEquals(ExtractionStatus.REJECTED, result.getStatus());
    assertNull(result.getExtractedFacts());
  }

  @Test
  void rejectFailsWhenNotInReviewState() {
    DocumentExtraction extraction =
        DocumentExtraction.request(DOCUMENT, 1, OWNER, NOW, "v1", "test-model", "product-facts-v1");
    givenExtraction(extraction);

    assertEquals(
        ErrorCode.INVALID_EXTRACTION_STATE,
        errorOf(() -> service.reject(DOCUMENT, EXTRACTION, OWNER)));
  }
}
