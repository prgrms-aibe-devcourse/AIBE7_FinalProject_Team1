package org.example.springtestci.ownership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.example.springtestci.auth.repository.UserRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.ownership.dto.AcceptTransferRequest;
import org.example.springtestci.ownership.dto.AcceptTransferResponse;
import org.example.springtestci.ownership.dto.CreateTransferRequest;
import org.example.springtestci.ownership.dto.CreateTransferResponse;
import org.example.springtestci.ownership.dto.VerifyTransferResponse;
import org.example.springtestci.ownership.entity.OwnershipTransfer;
import org.example.springtestci.ownership.entity.TransferStatus;
import org.example.springtestci.ownership.entity.TransferType;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OwnershipTransferServiceTest {

  @Mock private OwnershipTransferRepository transferRepository;
  @Mock private ProductRepository productRepository;
  @Mock private UserRepository userRepository;
  @Mock private TradeAuditRepository tradeAuditRepository;

  private OwnershipTransferService service;

  @BeforeEach
  void setUp() {
    service =
        new OwnershipTransferService(
            transferRepository, productRepository, userRepository, tradeAuditRepository);
  }

  @Test
  @DisplayName("소유자가 이전을 요청하면 원문 토큰과 링크가 발급되고 DB에는 SHA-256 해시가 저장된다")
  void createTransfer_success() {
    long productId = 10L;
    long ownerId = 1L;
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(ownerId));
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
  @DisplayName("이미 대기 중인 소유권 이전 요청이 있으면 중복 생성이 거부된다")
  void createTransfer_duplicatePending() {
    long productId = 10L;
    long ownerId = 1L;
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(ownerId));
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

    VerifyTransferResponse verify = service.verifyTransfer(rawToken);

    assertThat(verify.transferId()).isEqualTo(100L);
    assertThat(verify.productTitle()).isEqualTo("맥북 프로 M1");
    assertThat(verify.canAccept()).isTrue();
    assertThat(verify.isExpired()).isFalse();
  }

  @Test
  @DisplayName("구매자가 일회성 토큰으로 이전을 수락하면 소유자가 변경되고 상태가 COMPLETED로 전환된다")
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

    given(transferRepository.findByTokenHashForUpdate(tokenHash)).willReturn(Optional.of(transfer));
    given(productRepository.findOwnerId(productId)).willReturn(Optional.of(fromUserId));

    AcceptTransferResponse accepted =
        service.acceptTransfer(buyerId, new AcceptTransferRequest(rawToken));

    assertThat(accepted.transferId()).isEqualTo(200L);
    assertThat(accepted.productId()).isEqualTo(productId);
    assertThat(accepted.newOwnerId()).isEqualTo(buyerId);
    assertThat(transfer.getStatus()).isEqualTo(TransferStatus.COMPLETED);

    verify(productRepository).updateOwner(productId, buyerId);
    verify(tradeAuditRepository)
        .log(
            eq(productId),
            eq(buyerId),
            eq(200L),
            eq("OWNERSHIP_TRANSFERRED"),
            eq("fromOwner=1"),
            eq("toOwner=2"),
            anyString());
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

    given(transferRepository.findByIdForUpdate(transferId)).willReturn(Optional.of(transfer));

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
