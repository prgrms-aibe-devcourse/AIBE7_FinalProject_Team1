package org.example.springtestci.ownership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.example.springtestci.auth.repository.UserRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.history.service.InternalTransferHistoryWriter;
import org.example.springtestci.ownership.dto.AcceptTransferRequest;
import org.example.springtestci.ownership.dto.AcceptTransferResponse;
import org.example.springtestci.ownership.dto.CreateTransferRequest;
import org.example.springtestci.ownership.dto.CreateTransferResponse;
import org.example.springtestci.ownership.dto.VerifyTransferResponse;
import org.example.springtestci.ownership.entity.OwnershipTransfer;
import org.example.springtestci.ownership.entity.TransferStatus;
import org.example.springtestci.ownership.entity.TransferType;
import org.example.springtestci.ownership.event.OwnershipTransferred;
import org.example.springtestci.ownership.repository.DirectTransferGuard;
import org.example.springtestci.ownership.repository.InternalTradeOwnershipRepository;
import org.example.springtestci.ownership.repository.OwnershipTransferRepository;
import org.example.springtestci.product.repository.ProductRepository;
import org.example.springtestci.trade.repository.TradeAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OwnershipTransferServiceTest {

  private OwnershipTransfer approvalFixture() {
    var transfer =
        OwnershipTransfer.create(
            10L,
            1L,
            2L,
            "approval-hash",
            TransferType.DIRECT,
            null,
            Instant.now().plusSeconds(3600));
    ReflectionTestUtils.setField(transfer, "id", 200L);
    transfer.acceptRecipient(2L, Instant.now());
    given(transferRepository.findProductIdByTransferId(200L)).willReturn(Optional.of(10L));
    given(productRepository.lockForTransfer(10L)).willReturn(true);
    given(transferRepository.findByIdForUpdate(200L)).willReturn(Optional.of(transfer));
    return transfer;
  }

  @Test
  void ownerApprovalCompletesAllEffectsOnce() {
    var transfer = approvalFixture();
    var product =
        org.mockito.Mockito.mock(org.example.springtestci.product.dto.ProductResponse.class);
    given(product.factsVersion()).willReturn(7L);
    given(productRepository.findOwned(10L, 1L)).willReturn(Optional.of(product));
    given(userRepository.existsById(2L)).willReturn(true);
    given(userRepository.existsById(1L)).willReturn(true);
    var request = new org.example.springtestci.ownership.dto.ApproveTransferRequest(2L, 7L);
    var completed = service.approveTransfer(1L, 200L, request);
    assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);
    assertThat(service.approveTransfer(1L, 200L, request)).isEqualTo(completed);
    var order =
        inOrder(
            productRepository,
            transferRepository,
            historyWriter,
            effects,
            events,
            tradeAuditRepository);
    order.verify(productRepository).updateOwner(10L, 2L);
    order.verify(transferRepository).flush();
    order
        .verify(historyWriter)
        .recordCompletedTransfer(
            new InternalTransferHistoryWriter.Command(10L, 200L, 1L, 2L, completed.completedAt()));
    order.verify(effects).protectDocuments(10L, completed.completedAt());
    order.verify(events).publishEvent(new OwnershipTransferred(10L, 1L, 2L));
    verify(tradeAuditRepository)
        .log(eq(10L), eq(1L), eq(200L), eq("OWNERSHIP_TRANSFERRED"), any(), any(), any());
  }

  @Test
  void approvalRejectsWrongActorRecipientAndChangedFacts() {
    approvalFixture();
    var request = new org.example.springtestci.ownership.dto.ApproveTransferRequest(2L, 7L);
    assertThatThrownBy(() -> service.approveTransfer(2L, 200L, request))
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
    var product =
        org.mockito.Mockito.mock(org.example.springtestci.product.dto.ProductResponse.class);
    given(product.factsVersion()).willReturn(8L);
    given(productRepository.findOwned(10L, 1L)).willReturn(Optional.of(product));
    assertThatThrownBy(() -> service.approveTransfer(1L, 200L, request))
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    assertThatThrownBy(
            () ->
                service.approveTransfer(
                    1L,
                    200L,
                    new org.example.springtestci.ownership.dto.ApproveTransferRequest(3L, 8L)))
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    verify(productRepository, never()).updateOwner(anyLong(), anyLong());
    verifyNoInteractions(historyWriter, effects, events);
  }

  @Test
  void approvalRequiresConsentAndUnexpiredNonSaleRequest() {
    var transfer = approvalFixture();
    var request = new org.example.springtestci.ownership.dto.ApproveTransferRequest(2L, 7L);
    ReflectionTestUtils.setField(transfer, "recipientAcceptedAt", null);
    assertThatThrownBy(() -> service.approveTransfer(1L, 200L, request))
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    transfer.acceptRecipient(2L, Instant.now());
    given(guard.lockSales(10L)).willReturn(true);
    assertThatThrownBy(() -> service.approveTransfer(1L, 200L, request))
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    ReflectionTestUtils.setField(transfer, "expiresAt", Instant.EPOCH);
    assertThatThrownBy(() -> service.approveTransfer(1L, 200L, request))
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_EXPIRED);
    verifyNoInteractions(historyWriter, effects, events);
  }

  @Test
  void verifyRejectsOtherDesignatedRecipientBeforeSummaryLookup() {
    var transfer =
        OwnershipTransfer.create(
            10L, 1L, 2L, "hash", TransferType.DIRECT, null, Instant.now().plusSeconds(3600));
    given(transferRepository.findByTokenHash(anyString())).willReturn(Optional.of(transfer));
    assertThatThrownBy(() -> service.verifyTransfer(3L, "token"))
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
    verifyNoInteractions(productRepository, userRepository);
  }

  @Mock private OwnershipTransferRepository transferRepository;
  @Mock private ProductRepository productRepository;
  @Mock private UserRepository userRepository;
  @Mock private TradeAuditRepository tradeAuditRepository;
  @Mock private InternalTransferHistoryWriter historyWriter;
  @Mock private ApplicationEventPublisher events;
  @Mock private DirectTransferGuard guard;
  @Mock private InternalTradeOwnershipRepository effects;

  private OwnershipTransferService service;

  @BeforeEach
  void setUp() {
    service =
        new OwnershipTransferService(
            transferRepository,
            productRepository,
            userRepository,
            tradeAuditRepository,
            guard,
            effects,
            Clock.systemUTC(),
            historyWriter,
            events);
  }

  @Test
  @DisplayName("소유자가 이전을 요청하면 원문 토큰과 링크가 발급되고 DB에는 SHA-256 해시가 저장된다")
  void createTransfer_success() {
    long productId = 10L;
    long ownerId = 1L;
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(ownerId));
    given(productRepository.lockOwned(productId, ownerId)).willReturn(true);
    given(transferRepository.findByProductIdAndStatus(productId, TransferStatus.PENDING))
        .willReturn(Optional.empty());
    given(transferRepository.saveAndFlush(any(OwnershipTransfer.class)))
        .willAnswer(
            inv -> {
              OwnershipTransfer t = inv.getArgument(0);
              ReflectionTestUtils.setField(t, "id", 100L);
              return t;
            });

    CreateTransferRequest request = new CreateTransferRequest(productId, null, null);
    CreateTransferResponse response = service.createTransfer(ownerId, request);

    assertThat(response.productId()).isEqualTo(productId);
    assertThat(response.transferId()).isEqualTo(100L);
    assertThat(response.rawToken()).isNotBlank();
    assertThat(response.linkUrl()).contains(response.rawToken());

    ArgumentCaptor<OwnershipTransfer> captor = ArgumentCaptor.forClass(OwnershipTransfer.class);
    verify(transferRepository).saveAndFlush(captor.capture());

    OwnershipTransfer saved = captor.getValue();
    assertThat(saved.getProductId()).isEqualTo(productId);
    assertThat(saved.getFromUserId()).isEqualTo(ownerId);
    assertThat(saved.getTokenHash())
        .isEqualTo(OwnershipTransferService.hashToken(response.rawToken()));
    assertThat(saved.getTokenHash()).isNotEqualTo(response.rawToken()); // 원문이 아닌 해시 저장(SEC-07)
    assertThat(saved.getStatus()).isEqualTo(TransferStatus.PENDING);

    verify(tradeAuditRepository)
        .log(
            eq(productId),
            eq(ownerId),
            anyLong(),
            eq("OWNERSHIP_TRANSFER_CREATED"),
            any(),
            any(),
            any());
  }

  @Test
  @DisplayName("소유자가 아닌 사용자가 이전 생성을 요청하면 거부된다")
  void createTransfer_accessDenied() {
    long productId = 10L;
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(1L));

    CreateTransferRequest request = new CreateTransferRequest(productId, null, null);

    assertThatThrownBy(() -> service.createTransfer(999L, request))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
  }

  @Test
  void externalTransferCannotBindAClientSuppliedPurchaseRequest() {
    assertThatThrownBy(() -> service.createTransfer(1L, new CreateTransferRequest(10L, 2L, 123L)))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    verifyNoInteractions(productRepository, transferRepository, tradeAuditRepository, events);
  }

  @Test
  void createTransfer_openListingIsBlockedBeforeAnyTransferIsWritten() {
    given(productRepository.findOwnerId(10L)).willReturn(Optional.of(1L));
    given(productRepository.lockOwned(10L, 1L)).willReturn(true);
    given(guard.lockSales(10L)).willReturn(true);

    assertThatThrownBy(() -> service.createTransfer(1L, new CreateTransferRequest(10L, null, null)))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    var order = inOrder(productRepository, guard);
    order.verify(productRepository).lockOwned(10L, 1L);
    order.verify(guard).lockSales(10L);
    verify(transferRepository, never()).saveAndFlush(any());
  }

  @Test
  void createTransfer_rechecksOwnershipWhenAcquiringTheProductLock() {
    given(productRepository.findOwnerId(10L)).willReturn(Optional.of(1L));
    given(productRepository.lockOwned(10L, 1L)).willReturn(false);
    assertThatThrownBy(() -> service.createTransfer(1L, new CreateTransferRequest(10L, null, null)))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);
    verify(transferRepository, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("이미 대기 중인 소유권 이전 요청이 있으면 중복 생성이 거부된다")
  void createTransfer_duplicatePending() {
    long productId = 10L;
    long ownerId = 1L;
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(ownerId));
    given(productRepository.lockOwned(productId, ownerId)).willReturn(true);
    OwnershipTransfer pending =
        OwnershipTransfer.create(
            productId,
            ownerId,
            null,
            "hash",
            TransferType.DIRECT,
            null,
            Instant.now().plus(1, ChronoUnit.HOURS));
    given(transferRepository.findByProductIdAndStatus(productId, TransferStatus.PENDING))
        .willReturn(Optional.of(pending));

    CreateTransferRequest request = new CreateTransferRequest(productId, null, null);

    assertThatThrownBy(() -> service.createTransfer(ownerId, request))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_ALREADY_PENDING);
  }

  @Test
  @DisplayName("만료된 대기 요청은 EXPIRED로 정리되고 새 이전 요청을 발급할 수 있다")
  void createTransfer_expiredPendingDoesNotBlock() {
    long productId = 10L;
    long ownerId = 1L;
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(ownerId));
    given(productRepository.lockOwned(productId, ownerId)).willReturn(true);
    OwnershipTransfer stale =
        OwnershipTransfer.create(
            productId,
            ownerId,
            null,
            "old-hash",
            TransferType.DIRECT,
            null,
            Instant.now().minus(1, ChronoUnit.HOURS));
    given(transferRepository.findByProductIdAndStatus(productId, TransferStatus.PENDING))
        .willReturn(Optional.of(stale));
    given(transferRepository.saveAndFlush(any(OwnershipTransfer.class)))
        .willAnswer(inv -> inv.getArgument(0));

    service.createTransfer(ownerId, new CreateTransferRequest(productId, null, null));

    assertThat(stale.getStatus()).isEqualTo(TransferStatus.EXPIRED);
  }

  @Test
  @DisplayName("동시 발급이 유니크 제약에 걸리면 이미 대기 중인 이전 요청 오류로 바꾼다")
  void createTransfer_uniqueViolationBecomesAlreadyPending() {
    long productId = 10L;
    long ownerId = 1L;
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(ownerId));
    given(productRepository.lockOwned(productId, ownerId)).willReturn(true);
    given(transferRepository.findByProductIdAndStatus(productId, TransferStatus.PENDING))
        .willReturn(Optional.empty());
    given(transferRepository.saveAndFlush(any(OwnershipTransfer.class)))
        .willThrow(new DataIntegrityViolationException("uk_transfers_pending_product"));

    assertThatThrownBy(
            () -> service.createTransfer(ownerId, new CreateTransferRequest(productId, null, null)))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_ALREADY_PENDING);
  }

  @Test
  @DisplayName("이전 요청은 대기 중일 때만 슬롯을 차지하고 완료·취소·만료되면 비운다")
  void pendingSlotIsOccupiedOnlyWhilePending() {
    Instant now = Instant.now();
    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            10L, 1L, null, "hash", TransferType.DIRECT, null, now.plus(1, ChronoUnit.HOURS));
    assertThat(transfer.getPendingSlot()).isEqualTo((short) 1);

    transfer.complete(2L, now);
    assertThat(transfer.getPendingSlot()).isNull();

    OwnershipTransfer cancelled =
        OwnershipTransfer.create(
            10L, 1L, null, "hash2", TransferType.DIRECT, null, now.plus(1, ChronoUnit.HOURS));
    cancelled.cancel(now);
    assertThat(cancelled.getPendingSlot()).isNull();

    OwnershipTransfer expired =
        OwnershipTransfer.create(
            10L, 1L, null, "hash3", TransferType.DIRECT, null, now.minus(1, ChronoUnit.HOURS));
    expired.expire();
    assertThat(expired.getPendingSlot()).isNull();
  }

  @Test
  @DisplayName("발급 이후 제품 소유자가 바뀌었다면 이전 수락이 거부된다")
  void acceptTransfer_ownerChangedSinceIssue() {
    String rawToken = "stale-owner-token";
    String tokenHash = OwnershipTransferService.hashToken(rawToken);
    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            10L,
            1L,
            null,
            tokenHash,
            TransferType.DIRECT,
            null,
            Instant.now().plus(1, ChronoUnit.DAYS));
    given(transferRepository.findProductIdByTokenHash(tokenHash)).willReturn(Optional.of(10L));
    given(productRepository.lockForTransfer(10L)).willReturn(true);
    given(transferRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(transfer));
    given(productRepository.findOwnerId(10L)).willReturn(Optional.of(99L));

    assertThatThrownBy(() -> service.acceptTransfer(2L, new AcceptTransferRequest(rawToken)))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_EXPIRED);
  }

  @Test
  @DisplayName("원문 토큰으로 이전 요청의 유효성을 조회할 수 있다")
  void verifyTransfer_success() {
    String rawToken = "test-raw-token-12345678901234567890";
    String tokenHash = OwnershipTransferService.hashToken(rawToken);

    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            10L,
            1L,
            null,
            tokenHash,
            TransferType.DIRECT,
            null,
            Instant.now().plus(1, ChronoUnit.DAYS));
    ReflectionTestUtils.setField(transfer, "id", 100L);

    given(transferRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(transfer));
    given(productRepository.findTitle(10L)).willReturn(Optional.of("맥북 프로 M1"));
    given(productRepository.findOwnerId(10L)).willReturn(Optional.of(1L));

    VerifyTransferResponse verify = service.verifyTransfer(2L, rawToken);

    assertThat(verify.transferId()).isEqualTo(100L);
    assertThat(verify.productTitle()).isEqualTo("맥북 프로 M1");
    assertThat(verify.canAccept()).isTrue();
    assertThat(verify.isExpired()).isFalse();
  }

  @Test
  @DisplayName("수신자가 지정된 이전 요청은 조회 시점에도 지정 수신자와 발급자 외에는 거부한다")
  void verifyTransfer_deniesNonRecipient() {
    String rawToken = "designated-raw-token-1234567890";
    String tokenHash = OwnershipTransferService.hashToken(rawToken);
    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            10L,
            1L,
            2L,
            tokenHash,
            TransferType.DIRECT,
            null,
            Instant.now().plus(1, ChronoUnit.DAYS));
    given(transferRepository.findByTokenHash(tokenHash)).willReturn(Optional.of(transfer));

    assertThatThrownBy(() -> service.verifyTransfer(3L, rawToken))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
  }

  @Test
  @DisplayName("수신자 수락은 동의만 저장하고 소유자 승인을 기다린다")
  void acceptTransfer_success() {
    String rawToken = "accept-raw-token-1234567890";
    String tokenHash = OwnershipTransferService.hashToken(rawToken);
    long productId = 10L;
    long fromUserId = 1L;
    long buyerId = 2L;

    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            productId,
            fromUserId,
            null,
            tokenHash,
            TransferType.DIRECT,
            null,
            Instant.now().plus(1, ChronoUnit.DAYS));
    ReflectionTestUtils.setField(transfer, "id", 200L);

    given(transferRepository.findProductIdByTokenHash(tokenHash)).willReturn(Optional.of(10L));
    given(productRepository.lockForTransfer(10L)).willReturn(true);
    given(transferRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(transfer));
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(fromUserId));
    given(userRepository.existsById(buyerId)).willReturn(true);

    AcceptTransferResponse accepted =
        service.acceptTransfer(buyerId, new AcceptTransferRequest(rawToken));

    assertThat(accepted.transferId()).isEqualTo(200L);
    assertThat(accepted.productId()).isEqualTo(productId);
    assertThat(accepted.stage()).isEqualTo("AWAITING_OWNER_APPROVAL");
    assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
    assertThat(transfer.getToUserId()).isEqualTo(buyerId);
    assertThat(transfer.getRecipientAcceptedAt()).isNotNull();
    verify(productRepository, never()).updateOwner(anyLong(), anyLong());
    verifyNoInteractions(historyWriter, effects, events);
    service.acceptTransfer(buyerId, new AcceptTransferRequest(rawToken));
    verify(tradeAuditRepository)
        .log(
            eq(productId),
            eq(buyerId),
            eq(200L),
            eq("OWNERSHIP_RECIPIENT_ACCEPTED"),
            any(),
            any(),
            any());
  }

  @Test
  @DisplayName("자신의 제품에 대한 이전 요청을 직접 수락하려 하면 거부된다")
  void acceptTransfer_selfAcceptDenied() {
    String rawToken = "self-raw-token";
    String tokenHash = OwnershipTransferService.hashToken(rawToken);
    long fromUserId = 1L;

    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            10L,
            fromUserId,
            null,
            tokenHash,
            TransferType.DIRECT,
            null,
            Instant.now().plus(1, ChronoUnit.DAYS));

    given(transferRepository.findProductIdByTokenHash(tokenHash)).willReturn(Optional.of(10L));
    given(productRepository.lockForTransfer(10L)).willReturn(true);
    given(transferRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(transfer));

    assertThatThrownBy(
            () -> service.acceptTransfer(fromUserId, new AcceptTransferRequest(rawToken)))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_SELF_ACCEPT);
  }

  @Test
  @DisplayName("만료된 이전 토큰은 수락할 수 없다")
  void acceptTransfer_expired() {
    String rawToken = "expired-token";
    String tokenHash = OwnershipTransferService.hashToken(rawToken);

    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            10L,
            1L,
            null,
            tokenHash,
            TransferType.DIRECT,
            null,
            Instant.now().minus(1, ChronoUnit.HOURS)); // 이미 만료

    given(transferRepository.findProductIdByTokenHash(tokenHash)).willReturn(Optional.of(10L));
    given(productRepository.lockForTransfer(10L)).willReturn(true);
    given(transferRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(transfer));

    assertThatThrownBy(() -> service.acceptTransfer(2L, new AcceptTransferRequest(rawToken)))
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", ErrorCode.OWNERSHIP_EXPIRED);
  }

  @Test
  @DisplayName("발급자가 이전 요청을 취소하면 CANCELLED로 변경되고 감사 로그가 기록된다")
  void cancelTransfer_success() {
    long transferId = 300L;
    long ownerId = 1L;
    long productId = 10L;

    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            productId,
            ownerId,
            null,
            "hash",
            TransferType.DIRECT,
            null,
            Instant.now().plus(1, ChronoUnit.DAYS));
    ReflectionTestUtils.setField(transfer, "id", transferId);

    given(transferRepository.findProductIdByTransferId(transferId))
        .willReturn(Optional.of(productId));
    given(productRepository.lockForTransfer(productId)).willReturn(true);
    given(transferRepository.findByIdForUpdate(transferId)).willReturn(Optional.of(transfer));
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(ownerId));

    service.cancelTransfer(ownerId, transferId);

    assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CANCELLED);
    verify(tradeAuditRepository)
        .log(
            eq(productId),
            eq(ownerId),
            eq(transferId),
            eq("OWNERSHIP_TRANSFER_CANCELLED"),
            anyString(),
            anyString(),
            anyString());
  }
}
