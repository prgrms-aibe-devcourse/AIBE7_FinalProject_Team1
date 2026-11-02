package org.example.springtestci.ownership.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
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
import org.example.springtestci.ownership.event.OwnershipTransferred;
import org.example.springtestci.ownership.repository.OwnershipTransferRepository;
import org.example.springtestci.product.repository.ProductRepository;
import org.example.springtestci.trade.repository.TradeAuditRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class OwnershipTransferService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int TOKEN_EXPIRY_HOURS = 24;

  private final OwnershipTransferRepository transferRepository;
  private final ProductRepository productRepository;
  private final UserRepository userRepository;
  private final TradeAuditRepository tradeAuditRepository;
  private final ApplicationEventPublisher events;

  public OwnershipTransferService(
      OwnershipTransferRepository transferRepository,
      ProductRepository productRepository,
      UserRepository userRepository,
      TradeAuditRepository tradeAuditRepository,
      ApplicationEventPublisher events) {
    this.transferRepository = transferRepository;
    this.productRepository = productRepository;
    this.userRepository = userRepository;
    this.tradeAuditRepository = tradeAuditRepository;
    this.events = events;
  }

  /** 현재 소유자의 제품을 먼저 잠그고 판매 중·예약 중이 아닌 제품에만 외부 이전 요청을 만든다. */
  @Transactional
  public CreateTransferResponse createTransfer(long actorId, CreateTransferRequest req) {
    // 내부 거래 연결 ID는 거래 서비스만 정한다. 외부 링크 요청으로 다른 구매 요청을 점유할 수 없다.
    if (req.purchaseRequestId() != null) throw new CommonException(ErrorCode.INVALID_INPUT);
    Long ownerId =
        productRepository
            .findOwnerId(req.productId())
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));

    if (!Objects.equals(ownerId, actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

    // 게시와 같은 제품 잠금을 유지해야 두 요청이 상태 검사를 동시에 통과하지 않는다.
    if (!productRepository.lockOwned(req.productId(), actorId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    if (productRepository.hasOpenListing(req.productId())) {
      throw new CommonException(ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    }

    // 만료된 대기 요청이 새 이전 요청을 영영 막지 않도록 먼저 만료 처리한다.
    Instant now = Instant.now();
    transferRepository
        .findByProductIdAndStatus(req.productId(), TransferStatus.PENDING)
        .ifPresent(
            pending -> {
              if (!pending.isExpired(now)) {
                throw new CommonException(ErrorCode.OWNERSHIP_ALREADY_PENDING);
              }
              pending.expire();
              transferRepository.saveAndFlush(pending);
            });

    byte[] randomBytes = new byte[32];
    SECURE_RANDOM.nextBytes(randomBytes);
    String rawToken = HexFormat.of().formatHex(randomBytes);
    String tokenHash = hashToken(rawToken);

    Instant expiresAt = now.plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS);
    OwnershipTransfer transfer =
        OwnershipTransfer.create(
            req.productId(),
            actorId,
            req.toUserId(),
            tokenHash,
            TransferType.DIRECT,
            req.purchaseRequestId(),
            expiresAt);

    // 동시 요청이 위의 확인을 함께 통과해도 (product_id, pending_slot) 유니크가 하나만 남긴다.
    try {
      transfer = transferRepository.saveAndFlush(transfer);
    } catch (DataIntegrityViolationException e) {
      throw new CommonException(ErrorCode.OWNERSHIP_ALREADY_PENDING);
    }

    long transferId = transfer.getId() != null ? transfer.getId() : 0L;
    tradeAuditRepository.log(
        req.productId(),
        actorId,
        transferId,
        "OWNERSHIP_TRANSFER_CREATED",
        null,
        "status=PENDING",
        "소유권 이전 토큰 발급");

    String linkUrl = "/receive?token=" + rawToken;
    return new CreateTransferResponse(transferId, req.productId(), rawToken, expiresAt, linkUrl);
  }

  /**
   * 로그인한 사용자에게만 이전 요청 정보를 보여 준다. 수신자가 지정된 요청은 수락 시점이 아니라 조회 시점에도 지정 수신자(와 발급자)만 볼 수 있어, 토큰을 얻은 다른
   * 사용자에게 제품명·판매자 정보가 노출되지 않는다.
   */
  public VerifyTransferResponse verifyTransfer(long actorId, String rawToken) {
    String tokenHash = hashToken(rawToken);
    OwnershipTransfer transfer =
        transferRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

    if (transfer.getTransferType() != TransferType.DIRECT)
      throw new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND);

    if (transfer.getToUserId() != null
        && !Objects.equals(transfer.getToUserId(), actorId)
        && !Objects.equals(transfer.getFromUserId(), actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

    Instant now = Instant.now();
    boolean isExpired = transfer.isExpired(now);
    boolean isPending = transfer.getStatus() == TransferStatus.PENDING;
    boolean canAccept = isPending && !isExpired;

    String productTitle = productRepository.findTitle(transfer.getProductId()).orElse("등록된 제품");
    String fromUserNickname =
        userRepository
            .findById(transfer.getFromUserId())
            .map(u -> u.getNickname())
            .orElse("알 수 없는 사용자");

    return new VerifyTransferResponse(
        transfer.getId(),
        transfer.getProductId(),
        productTitle,
        transfer.getFromUserId(),
        fromUserNickname,
        transfer.getExpiresAt(),
        transfer.getStatus(),
        isExpired,
        canAccept);
  }

  /** 제품→이전 요청 순으로 잠근 뒤 수신자·유효 기간·현재 소유권을 다시 확인하고 함께 확정한다. */
  @Transactional
  public AcceptTransferResponse acceptTransfer(long actorId, AcceptTransferRequest req) {
    String tokenHash = hashToken(req.token());
    long productId =
        transferRepository
            .findProductIdByTokenHash(tokenHash)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));
    lockTransferProduct(productId);
    OwnershipTransfer transfer =
        transferRepository
            .findByTokenHashForUpdate(tokenHash)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

    Instant now = Instant.now();
    if (transfer.getTransferType() != TransferType.DIRECT)
      throw new CommonException(ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    if (transfer.getStatus() != TransferStatus.PENDING || transfer.isExpired(now)) {
      throw new CommonException(ErrorCode.OWNERSHIP_EXPIRED);
    }

    if (Objects.equals(transfer.getFromUserId(), actorId)) {
      throw new CommonException(ErrorCode.OWNERSHIP_SELF_ACCEPT);
    }

    if (transfer.getToUserId() != null && !Objects.equals(transfer.getToUserId(), actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

    // 발급 이후 소유자가 바뀌었다면 이전 요청은 더 이상 유효하지 않다.
    Long currentOwnerId = productRepository.findOwnerId(transfer.getProductId()).orElse(null);
    if (!Objects.equals(currentOwnerId, transfer.getFromUserId())) {
      throw new CommonException(ErrorCode.OWNERSHIP_EXPIRED);
    }

    // 소유자 변경 및 이전 완료
    productRepository.updateOwner(transfer.getProductId(), actorId);
    transfer.complete(actorId, now);

    // 초안 삭제처럼 필수인 후처리는 커밋 전에 실행한다. 실패하면 소유자·이전 상태도 함께 되돌린다.
    events.publishEvent(
        new OwnershipTransferred(transfer.getProductId(), transfer.getFromUserId(), actorId));

    tradeAuditRepository.log(
        transfer.getProductId(),
        actorId,
        transfer.getId(),
        "OWNERSHIP_TRANSFERRED",
        "fromOwner=" + transfer.getFromUserId(),
        "toOwner=" + actorId,
        "소유권 이전 완료");

    return new AcceptTransferResponse(
        transfer.getId(), transfer.getProductId(), actorId, transfer.getCompletedAt());
  }

  /** 제품→이전 요청 잠금 아래 발급자의 PENDING 요청만 취소한다. 감사 기록도 같은 트랜잭션이다. */
  @Transactional
  public void cancelTransfer(long actorId, Long transferId) {
    long productId =
        transferRepository
            .findProductIdByTransferId(transferId)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));
    lockTransferProduct(productId);
    OwnershipTransfer transfer =
        transferRepository
            .findByIdForUpdate(transferId)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

    if (!Objects.equals(transfer.getFromUserId(), actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

    // 내부 예약은 P3/P4에서 거래·판매글과 함께 취소·완료해야 한다.
    if (transfer.getTransferType() != TransferType.DIRECT)
      throw new CommonException(ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);

    if (transfer.getStatus() != TransferStatus.PENDING) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }

    Instant now = Instant.now();
    transfer.cancel(now);

    tradeAuditRepository.log(
        transfer.getProductId(),
        actorId,
        transfer.getId(),
        "OWNERSHIP_TRANSFER_CANCELLED",
        "status=PENDING",
        "status=CANCELLED",
        "소유권 이전 취소");
  }

  private void lockTransferProduct(long productId) {
    if (!productRepository.lockForTransfer(productId)) {
      throw new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND);
    }
  }

  public static String hashToken(String rawToken) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not found", e);
    }
  }
}
