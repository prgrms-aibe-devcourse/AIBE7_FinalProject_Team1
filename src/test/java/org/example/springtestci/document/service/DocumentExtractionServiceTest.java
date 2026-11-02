package org.example.springtestci.document.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DocumentExtractionServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
  private static final Long OWNER = 1L;
  private static final Long OTHER = 2L;
  private static final Long PRODUCT = 100L;
  private static final Long DOCUMENT = 10L;

  @Mock private DocumentRepository documentRepository;
  @Mock private DocumentExtractionRepository extractionRepository;
  @Mock private ProductOwnerReader productOwnerReader;
  @Mock private ObjectProvider<DocumentFactExtractor> extractorProvider;
  @Mock private DocumentFactExtractor extractor;
  @Mock private DocumentExtractionProcessor processor;

  private DocumentExtractionService service;

  @BeforeEach
  void setUp() {
    lenient().when(extractorProvider.getIfAvailable()).thenReturn(extractor);
    lenient().when(extractor.modelName()).thenReturn("test-model");
    lenient().when(productOwnerReader.findOwnerId(PRODUCT)).thenReturn(Optional.of(OWNER));
    lenient().when(documentRepository.findProductIdById(DOCUMENT)).thenReturn(Optional.of(PRODUCT));
    lenient()
        .when(productOwnerReader.lockOwnerId(PRODUCT))
        .thenAnswer(invocation -> productOwnerReader.findOwnerId(PRODUCT));
    service =
        new DocumentExtractionService(
            documentRepository,
            extractionRepository,
            productOwnerReader,
            extractorProvider,
            processor,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Document document() {
    return Document.register(PRODUCT, OWNER, "documents/100/k.png", "image/png", 10, NOW);
  }

  private ExtractionCreateRequest request() {
    return new ExtractionCreateRequest(true, "v1", DocumentType.RECEIPT);
  }

  private ErrorCode errorOf(Runnable action) {
    return assertThrows(CommonException.class, action::run).errorCode();
  }

  @Test
  void requestSavesQueuedExtractionAndStartsProcessing() {
    when(documentRepository.findByIdForUpdate(DOCUMENT)).thenReturn(Optional.of(document()));
    when(extractionRepository.existsByDocumentIdAndStatusIn(any(), anyCollection()))
        .thenReturn(false);
    when(extractionRepository.save(any(DocumentExtraction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    DocumentExtraction saved = service.request(DOCUMENT, OWNER, request());

    assertEquals(ExtractionStatus.QUEUED, saved.getStatus());
    assertEquals("test-model", saved.getModelName());
    assertEquals("v1", saved.getConsentNoticeVersion());
    assertEquals(NOW, saved.getConsentedAt());
    assertEquals(DocumentExtractionService.SCHEMA_VERSION, saved.getSchemaVersion());
    // 트랜잭션 동기화가 없는 단위 테스트에서는 즉시 처리를 시작한다.
    verify(processor).processAsync(saved.getId(), DocumentType.RECEIPT);
  }

  @Test
  void rejectedExecutionFailsExtractionImmediately() {
    when(documentRepository.findByIdForUpdate(DOCUMENT)).thenReturn(Optional.of(document()));
    when(extractionRepository.existsByDocumentIdAndStatusIn(any(), anyCollection()))
        .thenReturn(false);
    when(extractionRepository.save(any(DocumentExtraction.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    doThrow(new RejectedExecutionException("queue full"))
        .when(processor)
        .processAsync(any(), any());

    DocumentExtraction saved = service.request(DOCUMENT, OWNER, request());

    verify(processor).failRejected(saved.getId());
  }

  @Test
  void requestFailsWhenAiIsNotConfigured() {
    when(extractorProvider.getIfAvailable()).thenReturn(null);

    assertEquals(
        ErrorCode.DOCUMENT_AI_UNAVAILABLE,
        errorOf(() -> service.request(DOCUMENT, OWNER, request())));
    verify(extractionRepository, never()).save(any());
  }

  @Test
  void nonOwnerSeesNotFound() {

    assertEquals(
        ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> service.request(DOCUMENT, OTHER, request())));
    verify(processor, never()).processAsync(any(), any());
  }

  @Test
  void redactionRequiredDocumentCannotBeExtracted() {
    Document document = document();
    document.requireRedaction(NOW);
    when(documentRepository.findByIdForUpdate(DOCUMENT)).thenReturn(Optional.of(document));

    assertEquals(
        ErrorCode.DOCUMENT_REDACTION_REQUIRED,
        errorOf(() -> service.request(DOCUMENT, OWNER, request())));
  }

  @Test
  void activeExtractionBlocksNewRequest() {
    when(documentRepository.findByIdForUpdate(DOCUMENT)).thenReturn(Optional.of(document()));
    when(extractionRepository.existsByDocumentIdAndStatusIn(any(), anyCollection()))
        .thenReturn(true);

    assertEquals(
        ErrorCode.DOCUMENT_EXTRACTION_ACTIVE,
        errorOf(() -> service.request(DOCUMENT, OWNER, request())));
    verify(extractionRepository, never()).save(any());
  }

  @Test
  void getAndLatestRequireCurrentOwner() {
    when(documentRepository.findById(DOCUMENT)).thenReturn(Optional.of(document()));

    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> service.get(DOCUMENT, 5L, OTHER)));
    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> service.latest(DOCUMENT, OTHER)));
  }

  @Test
  void latestReturnsNotFoundWhenNoExtractionExists() {
    when(documentRepository.findById(DOCUMENT)).thenReturn(Optional.of(document()));
    when(extractionRepository.findFirstByDocumentIdOrderByIdDesc(DOCUMENT))
        .thenReturn(Optional.empty());

    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> service.latest(DOCUMENT, OWNER)));
  }
}
