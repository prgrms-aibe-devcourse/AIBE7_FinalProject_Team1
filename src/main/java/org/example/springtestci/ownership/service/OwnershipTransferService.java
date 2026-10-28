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
import org.example.springtestci.ownership.repository.OwnershipTransferRepository;
import org.example.springtestci.product.repository.ProductRepository;
import org.example.springtestci.trade.repository.TradeAuditRepository;
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

  public OwnershipTransferService(
      OwnershipTransferRepository transferRepository,
      ProductRepository productRepository,
      UserRepository userRepository,
      TradeAuditRepository tradeAuditRepository) {
    this.transferRepository = transferRepository;
    this.productRepository = productRepository;
    this.userRepository = userRepository;
    this.tradeAuditRepository = tradeAuditRepository;
  }

  @Transactional
  public CreateTransferResponse createTransfer(long actorId, CreateTransferRequest req) {
    Long ownerId =
        productRepository
            .findOwnerId(req.productId())
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));

    if (!Objects.equals(ownerId, actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
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

  public VerifyTransferResponse verifyTransfer(String rawToken) {
    String tokenHash = hashToken(rawToken);
    OwnershipTransfer transfer =
        transferRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

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

  @Transactional
  public AcceptTransferResponse acceptTransfer(long actorId, AcceptTransferRequest req) {
    String tokenHash = hashToken(req.token());
    OwnershipTransfer transfer =
        transferRepository
            .findByTokenHashForUpdate(tokenHash)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

    Instant now = Instant.now();
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

  @Transactional
  public void cancelTransfer(long actorId, Long transferId) {
    OwnershipTransfer transfer =
        transferRepository
            .findByIdForUpdate(transferId)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

    if (!Objects.equals(transfer.getFromUserId(), actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

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
