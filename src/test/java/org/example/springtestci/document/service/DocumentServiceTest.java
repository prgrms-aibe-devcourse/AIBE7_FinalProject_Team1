package org.example.springtestci.document.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.storage.FileStorage;
import org.example.springtestci.document.dto.FileUrlResponse;
import org.example.springtestci.document.entity.Document;
import org.example.springtestci.document.entity.DocumentExtraction;
import org.example.springtestci.document.entity.DocumentReplacement;
import org.example.springtestci.document.entity.ExtractionStatus;
import org.example.springtestci.document.repository.DocumentExtractionRepository;
import org.example.springtestci.document.repository.DocumentReplacementRepository;
import org.example.springtestci.document.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");
  private static final Long OWNER = 1L;
  private static final Long OTHER = 2L;
  private static final Long PRODUCT = 100L;
  private static final byte[] PDF = "%PDF-1.7 sample".getBytes();

  @Mock private DocumentRepository documentRepository;
  @Mock private DocumentExtractionRepository extractionRepository;
  @Mock private DocumentReplacementRepository replacementRepository;
  @Mock private FileStorage fileStorage;
  @Mock private ObjectProvider<FileStorage> storageProvider;
  @Mock private ProductOwnerReader productOwnerReader;

  private DocumentService service;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.lenient().when(storageProvider.getIfAvailable()).thenReturn(fileStorage);
    service =
        new DocumentService(
            documentRepository,
            extractionRepository,
            replacementRepository,
            storageProvider,
            new DocumentFileValidator(),
            productOwnerReader,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new DocumentUrlSigner("test-document-secret"));
  }

  private Document document(Long uploader) {
    return Document.register(PRODUCT, uploader, "documents/100/k.pdf", "application/pdf", 10, NOW);
  }

  @Test
  void downloadRechecksOwnerVersionSignatureExpiryAndRedaction() throws IOException {
    var signer = new DocumentUrlSigner("test-document-secret");
    long expires = NOW.plusSeconds(300).getEpochSecond();
    String signature = signer.sign(5L, OWNER, 1, expires);
    ownerIs(OWNER);
    Document document = document(OWNER);
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    when(fileStorage.load("documents/100/k.pdf"))
        .thenReturn(new org.springframework.core.io.ByteArrayResource(PDF));
    assertEquals(
        "application/pdf", service.download(5L, OWNER, 1, expires, signature).contentType());
    assertEquals(
        ErrorCode.ACCESS_DENIED, errorOf(() -> service.download(5L, OTHER, 1, expires, signature)));
    assertEquals(
        ErrorCode.ACCESS_DENIED,
        errorOf(() -> service.download(5L, OWNER, 1, NOW.getEpochSecond(), signature)));
    when(productOwnerReader.lockOwnerId(PRODUCT)).thenReturn(Optional.of(OTHER));
    assertEquals(
        ErrorCode.RESOURCE_NOT_FOUND,
        errorOf(() -> service.download(5L, OWNER, 1, expires, signature)));
    when(productOwnerReader.lockOwnerId(PRODUCT)).thenReturn(Optional.of(OWNER));
    document.requireRedaction(NOW);
    assertEquals(
        ErrorCode.DOCUMENT_REDACTION_REQUIRED,
        errorOf(() -> service.download(5L, OWNER, 1, expires, signature)));
    document.replaceFile("documents/100/new.pdf", "application/pdf", PDF.length, NOW);
    assertEquals(
        ErrorCode.ACCESS_DENIED, errorOf(() -> service.download(5L, OWNER, 1, expires, signature)));
    verify(fileStorage).load("documents/100/k.pdf");
  }

  @Test
  void oldFileDeletionFailureDoesNotRestoreOldDownloadVersionOrProtection() throws IOException {
    Document document = document(OWNER);
    document.protect(NOW);
    ownerIs(OWNER);
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    when(fileStorage.store(eq("documents/100"), eq("pdf"), any(InputStream.class)))
        .thenReturn("documents/100/new.pdf");
    org.mockito.Mockito.doThrow(new IOException("cleanup failed"))
        .when(fileStorage)
        .delete("documents/100/k.pdf");
    replace(OWNER, true);
    assertTrue(document.isProtected());
    long expires = NOW.plusSeconds(300).getEpochSecond();
    String oldSignature = new DocumentUrlSigner("test-document-secret").sign(5L, OWNER, 1, expires);
    assertEquals(
        ErrorCode.ACCESS_DENIED,
        errorOf(() -> service.download(5L, OWNER, 1, expires, oldSignature)));
    verify(fileStorage, never()).load(anyString());
  }

  private ErrorCode errorOf(Runnable action) {
    return assertThrows(CommonException.class, action::run).errorCode();
  }

  @Test
  void signedFileRejectsTamperedIdentifiersExpiryAndSignatureBeforeStorageAccess()
      throws IOException {
    var signer = new DocumentUrlSigner("test-document-secret");
    long expires = NOW.plusSeconds(300).getEpochSecond();
    String signature = signer.sign(5L, OWNER, 1, expires);
    assertEquals(
        ErrorCode.ACCESS_DENIED, errorOf(() -> service.download(6L, OWNER, 1, expires, signature)));
    assertEquals(
        ErrorCode.ACCESS_DENIED, errorOf(() -> service.download(5L, OWNER, 2, expires, signature)));
    assertEquals(
        ErrorCode.ACCESS_DENIED,
        errorOf(() -> service.download(5L, OWNER, 1, expires + 1, signature)));
    assertEquals(
        ErrorCode.ACCESS_DENIED,
        errorOf(() -> service.download(5L, OWNER, 1, expires, "tampered")));
    long exactExpiry = NOW.getEpochSecond();
    assertEquals(
        ErrorCode.ACCESS_DENIED,
        errorOf(
            () ->
                service.download(
                    5L, OWNER, 1, exactExpiry, signer.sign(5L, OWNER, 1, exactExpiry))));
    verify(fileStorage, never()).load(anyString());
  }

  @Test
  void newFileIsRemovedWhenTransactionRollsBackAfterUploadReturns() throws IOException {
    ownerIs(OWNER);
    when(fileStorage.store(anyString(), anyString(), any(InputStream.class)))
        .thenReturn("documents/100/rollback.pdf");
    when(documentRepository.save(any(Document.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
    try {
      upload(OWNER, true);
      verify(fileStorage, never()).delete(anyString());
      for (var synchronization :
          org.springframework.transaction.support.TransactionSynchronizationManager
              .getSynchronizations()) {
        synchronization.afterCompletion(
            org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
      }
      verify(fileStorage).delete("documents/100/rollback.pdf");
    } finally {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .clearSynchronization();
    }
  }

  private Document upload(Long userId, boolean maskingConfirmed) {
    return service.upload(PRODUCT, userId, maskingConfirmed, "masked.pdf", "application/pdf", PDF);
  }

  private void ownerIs(Long ownerId) {
    org.mockito.Mockito.lenient()
        .when(productOwnerReader.findOwnerId(PRODUCT))
        .thenReturn(Optional.of(ownerId));
    org.mockito.Mockito.lenient()
        .when(productOwnerReader.lockOwnerId(PRODUCT))
        .thenReturn(Optional.of(ownerId));
    org.mockito.Mockito.lenient()
        .when(documentRepository.findProductIdById(5L))
        .thenReturn(Optional.of(PRODUCT));
  }

  @Test
  void 현재_소유자는_마스킹_확인한_사본을_업로드할_수_있다() throws IOException {
    ownerIs(OWNER);
    when(fileStorage.store(eq("documents/100"), eq("pdf"), any(InputStream.class)))
        .thenReturn("documents/100/k.pdf");
    when(documentRepository.save(any(Document.class))).thenAnswer(i -> i.getArgument(0));

    Document saved = upload(OWNER, true);

    assertEquals("documents/100/k.pdf", saved.getObjectKey());
    assertEquals(OWNER, saved.getUploaderId());
    assertEquals(PDF.length, saved.getByteSize());
    assertEquals(1, saved.getVersion());
    assertEquals(NOW, saved.getMaskConfirmedAt());
  }

  @Test
  void 업로드한_파일명은_저장_키에_남기지_않는다() throws IOException {
    ownerIs(OWNER);
    when(fileStorage.store(anyString(), anyString(), any(InputStream.class)))
        .thenReturn("documents/100/k.pdf");
    when(documentRepository.save(any(Document.class))).thenAnswer(i -> i.getArgument(0));

    Document saved = service.upload(PRODUCT, OWNER, true, "홍길동_영수증.pdf", "application/pdf", PDF);

    assertFalse(saved.getObjectKey().contains("홍길동"));
  }

  @Test
  void 다운로드_시점에_현재_소유자가_아니면_파일_접근을_거부한다() {
    when(documentRepository.findByObjectKey("documents/100/k.pdf"))
        .thenReturn(Optional.of(document(OWNER)));
    ownerIs(OWNER);

    service.requireCurrentOwnerOfFile("documents/100/k.pdf", OWNER);
    assertEquals(
        ErrorCode.RESOURCE_NOT_FOUND,
        errorOf(() -> service.requireCurrentOwnerOfFile("documents/100/k.pdf", OTHER)));
  }

  @Test
  void 문서에_없는_저장_키는_다운로드할_수_없다() {
    when(documentRepository.findByObjectKey("documents/100/none.pdf")).thenReturn(Optional.empty());

    assertEquals(
        ErrorCode.RESOURCE_NOT_FOUND,
        errorOf(() -> service.requireCurrentOwnerOfFile("documents/100/none.pdf", OWNER)));
  }

  @Test
  void 마스킹을_확인하지_않으면_업로드할_수_없다() {
    assertEquals(ErrorCode.MASKING_NOT_CONFIRMED, errorOf(() -> upload(OWNER, false)));
    verify(documentRepository, never()).save(any());
  }

  @Test
  void 소유자가_아니면_업로드할_수_없다() {
    ownerIs(OWNER);

    assertEquals(ErrorCode.ACCESS_DENIED, errorOf(() -> upload(OTHER, true)));
    verify(documentRepository, never()).save(any());
  }

  @Test
  void 없는_제품에는_업로드할_수_없다() {
    when(productOwnerReader.lockOwnerId(PRODUCT)).thenReturn(Optional.empty());

    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> upload(OWNER, true)));
  }

  @Test
  void 문서_수_제한을_넘으면_거부한다() {
    ownerIs(OWNER);
    when(documentRepository.countByProductId(PRODUCT))
        .thenReturn((long) DocumentService.MAX_DOCUMENTS_PER_PRODUCT);

    assertEquals(ErrorCode.DOCUMENT_LIMIT_EXCEEDED, errorOf(() -> upload(OWNER, true)));
  }

  @Test
  void 저장에_실패하면_업로드한_파일을_정리한다() throws IOException {
    ownerIs(OWNER);
    when(fileStorage.store(anyString(), anyString(), any(InputStream.class)))
        .thenReturn("documents/100/k.pdf");
    when(documentRepository.save(any(Document.class))).thenThrow(new IllegalStateException("db"));

    assertThrows(IllegalStateException.class, () -> upload(OWNER, true));

    verify(fileStorage).delete("documents/100/k.pdf");
  }

  @Test
  void 현재_소유자는_이전받은_사본을_포함해_모두_조회한다() {
    ownerIs(OWNER);
    when(documentRepository.findByProductIdOrderByIdDesc(PRODUCT))
        .thenReturn(List.of(document(OTHER), document(OWNER)));

    assertEquals(2, service.list(PRODUCT, OWNER).size());
  }

  @Test
  void 이전_소유자는_목록을_조회할_수_없다() {
    ownerIs(OWNER);

    assertEquals(ErrorCode.ACCESS_DENIED, errorOf(() -> service.list(PRODUCT, OTHER)));
  }

  @Test
  void 현재_소유자는_만료형_URL을_받는다() {
    when(documentRepository.findById(5L)).thenReturn(Optional.of(document(OTHER)));
    ownerIs(OWNER);

    FileUrlResponse response = service.createFileUrl(5L, OWNER);

    assertTrue(response.url().startsWith("/api/v1/documents/5/file?version=1&expires="));
    assertEquals(NOW.plus(DocumentService.FILE_URL_TTL), response.expiresAt());
  }

  @Test
  void 소유권이_이전되면_이전_소유자는_URL을_받을_수_없다() {
    when(documentRepository.findById(5L)).thenReturn(Optional.of(document(OTHER)));
    ownerIs(OWNER);

    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> service.createFileUrl(5L, OTHER)));
    verify(fileStorage, never()).createTemporaryUrl(anyString(), any());
  }

  @Test
  void 재마스킹이_필요한_사본은_열람할_수_없다() {
    Document document = document(OWNER);
    document.requireRedaction(NOW);
    when(documentRepository.findById(5L)).thenReturn(Optional.of(document));
    ownerIs(OWNER);

    assertEquals(
        ErrorCode.DOCUMENT_REDACTION_REQUIRED, errorOf(() -> service.createFileUrl(5L, OWNER)));
    verify(fileStorage, never()).createTemporaryUrl(anyString(), any());
  }

  @Test
  void 본인이_올린_보호되지_않은_사본은_삭제할_수_있다() throws IOException {
    Document document = document(OWNER);
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    ownerIs(OWNER);

    service.delete(5L, OWNER);

    // 외래키 때문에 자식 행(교체 기록, 추출)을 문서보다 먼저 지워야 한다.
    InOrder order = inOrder(replacementRepository, extractionRepository, documentRepository);
    order.verify(replacementRepository).deleteByDocumentId(document.getId());
    order.verify(extractionRepository).deleteByDocumentId(document.getId());
    order.verify(documentRepository).delete(document);
    verify(fileStorage).delete("documents/100/k.pdf");
  }

  @Test
  void 이력에_연결되어_보호된_사본은_삭제할_수_없다() throws IOException {
    Document document = document(OWNER);
    document.protect(NOW);
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    ownerIs(OWNER);

    assertEquals(ErrorCode.DOCUMENT_DELETE_NOT_ALLOWED, errorOf(() -> service.delete(5L, OWNER)));
    verify(documentRepository, never()).delete(any());
    verify(fileStorage, never()).delete(anyString());
  }

  @Test
  void 이전받은_사본은_삭제할_수_없다() throws IOException {
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document(OTHER)));
    ownerIs(OWNER);

    assertEquals(ErrorCode.DOCUMENT_DELETE_NOT_ALLOWED, errorOf(() -> service.delete(5L, OWNER)));
    verify(fileStorage, never()).delete(anyString());
  }

  @Test
  void 이전_소유자는_삭제할_수_없다() throws IOException {
    ownerIs(OWNER);

    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> service.delete(5L, OTHER)));
    verify(fileStorage, never()).delete(anyString());
  }

  private static byte[] sha256(byte[] content) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256").digest(content);
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private Document replace(Long userId, boolean maskingConfirmed) {
    return service.replace(5L, userId, maskingConfirmed, "masked.pdf", "application/pdf", PDF);
  }

  @Test
  void 소유자는_재마스킹한_사본으로_교체하고_이전_파일은_삭제한다() throws IOException {
    Document document = document(OWNER);
    document.protect(NOW);
    document.requireRedaction(NOW);
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    ownerIs(OWNER);
    when(fileStorage.store(eq("documents/100"), eq("pdf"), any(InputStream.class)))
        .thenReturn("documents/100/new.pdf");

    Document replaced = replace(OWNER, true);

    assertEquals("documents/100/new.pdf", replaced.getObjectKey());
    assertEquals(2, replaced.getVersion());
    assertFalse(replaced.isRedactionRequired());
    // 이력 연결(보호)은 교체해도 유지된다.
    assertTrue(replaced.isProtected());
    verify(fileStorage).delete("documents/100/k.pdf");
  }

  @Test
  void 교체하면_보호_여부와_파일_정보가_교체_기록에_남는다() throws IOException {
    Document document = document(OWNER);
    document.protect(NOW);
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    ownerIs(OWNER);
    when(fileStorage.store(eq("documents/100"), eq("pdf"), any(InputStream.class)))
        .thenReturn("documents/100/new.pdf");

    Document replaced = replace(OWNER, true);

    org.mockito.ArgumentCaptor<DocumentReplacement> captor =
        org.mockito.ArgumentCaptor.forClass(DocumentReplacement.class);
    verify(replacementRepository).save(captor.capture());
    DocumentReplacement record = captor.getValue();
    assertEquals(1, record.getFromVersion());
    assertEquals(2, record.getToVersion());
    assertEquals(OWNER, record.getReplacedById());
    assertTrue(record.isWasProtected());
    assertEquals(10, record.getOldByteSize());
    assertEquals(PDF.length, record.getNewByteSize());
    // 같은 내용이면 항상 같은 해시가 나와야 나중에 비교할 수 있다.
    assertEquals(java.util.HexFormat.of().formatHex(sha256(PDF)), record.getNewSha256());
    assertEquals(1, replaced.getReplacementCount());
    assertEquals(NOW, replaced.getLastReplacedAt());
  }

  @Test
  void 교체에_실패하면_교체_기록도_남기지_않는다() throws IOException {
    Document document = document(OWNER);
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    ownerIs(OWNER);
    when(fileStorage.store(eq("documents/100"), eq("pdf"), any(InputStream.class)))
        .thenReturn("documents/100/new.pdf");
    when(documentRepository.save(any(Document.class))).thenThrow(new IllegalStateException());

    assertThrows(IllegalStateException.class, () -> replace(OWNER, true));

    verify(replacementRepository, never()).save(any());
  }

  @Test
  void 교체하면_검토_대기_중인_추출은_반려된다() throws IOException {
    Document document = document(OWNER);
    DocumentExtraction pending =
        DocumentExtraction.request(5L, 1, OWNER, NOW, "v1", "model", "schema");
    pending.startProcessing();
    pending.completeExtraction(Map.of("productName", "값"));
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    when(extractionRepository.findByDocumentIdOrderByIdDesc(5L)).thenReturn(List.of(pending));
    ownerIs(OWNER);
    org.mockito.Mockito.lenient()
        .when(fileStorage.store(anyString(), anyString(), any(InputStream.class)))
        .thenReturn("documents/100/new.pdf");

    replace(OWNER, true);

    assertEquals(ExtractionStatus.REJECTED, pending.getStatus());
    assertNull(pending.getExtractedFacts());
  }

  @Test
  void 마스킹을_확인하지_않으면_교체할_수_없다() {
    assertEquals(ErrorCode.MASKING_NOT_CONFIRMED, errorOf(() -> replace(OWNER, false)));
    verify(documentRepository, never()).findByIdForUpdate(any());
  }

  @Test
  void 소유자가_아니면_교체할_수_없다() throws IOException {
    ownerIs(OWNER);

    assertEquals(ErrorCode.RESOURCE_NOT_FOUND, errorOf(() -> replace(OTHER, true)));
    verify(fileStorage, never()).delete(anyString());
  }

  @Test
  void 교체_중_저장에_실패하면_새_파일을_정리하고_이전_파일은_유지한다() throws IOException {
    Document document = document(OWNER);
    when(documentRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(document));
    ownerIs(OWNER);
    when(fileStorage.store(eq("documents/100"), eq("pdf"), any(InputStream.class)))
        .thenReturn("documents/100/new.pdf");
    when(documentRepository.save(any(Document.class))).thenThrow(new IllegalStateException());

    assertThrows(IllegalStateException.class, () -> replace(OWNER, true));

    verify(fileStorage).delete("documents/100/new.pdf");
    verify(fileStorage, never()).delete("documents/100/k.pdf");
  }

  @Test
  void 파일_저장소가_없으면_업로드와_열람은_503이다() {
    when(storageProvider.getIfAvailable()).thenReturn(null);
    ownerIs(OWNER);

    assertEquals(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE, errorOf(() -> upload(OWNER, true)));
    verify(documentRepository, never()).save(any());
  }
}
