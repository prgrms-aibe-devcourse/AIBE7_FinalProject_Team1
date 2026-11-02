package org.example.springtestci.ownership.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import org.example.springtestci.auth.repository.UserRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.history.service.InternalTransferHistoryWriter;
import org.example.springtestci.ownership.dto.AcceptTransferRequest;
import org.example.springtestci.ownership.dto.AcceptTransferResponse;
import org.example.springtestci.ownership.dto.CreateTransferRequest;
import org.example.springtestci.ownership.dto.CreateTransferResponse;
import org.example.springtestci.ownership.dto.OwnerTransferResponse;
import org.example.springtestci.ownership.dto.VerifyTransferResponse;
import org.example.springtestci.ownership.entity.OwnershipTransfer;
import org.example.springtestci.ownership.entity.TransferStatus;
import org.example.springtestci.ownership.entity.TransferType;
import org.example.springtestci.ownership.event.OwnershipTransferred;
import org.example.springtestci.ownership.repository.DirectTransferGuard;
import org.example.springtestci.ownership.repository.InternalTradeOwnershipRepository;
import org.example.springtestci.ownership.repository.OwnershipTransferRepository;
import org.example.springtestci.product.event.ProductFactsChanged;
import org.example.springtestci.product.repository.ProductRepository;
import org.example.springtestci.trade.repository.TradeAuditRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DIRECT 토큰 이전을 처리하며 INTERNAL 거래 이전은 별도의 거래 포트에 맡긴다. */
@Service
@Transactional(readOnly = true)
public class OwnershipTransferService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int TOKEN_EXPIRY_HOURS = 24;

  private final OwnershipTransferRepository transferRepository;
  private final ProductRepository productRepository;
  private final UserRepository userRepository;
  private final TradeAuditRepository tradeAuditRepository;
  private final DirectTransferGuard guard;
  private final InternalTradeOwnershipRepository effects;
  private final Clock clock;
  private final InternalTransferHistoryWriter historyWriter;
  private final ApplicationEventPublisher events;

  public OwnershipTransferService(
      OwnershipTransferRepository transferRepository,
      ProductRepository productRepository,
      UserRepository userRepository,
      TradeAuditRepository tradeAuditRepository,
      DirectTransferGuard guard,
      InternalTradeOwnershipRepository effects,
      Clock clock,
      InternalTransferHistoryWriter historyWriter,
      ApplicationEventPublisher events) {
    this.transferRepository = transferRepository;
    this.productRepository = productRepository;
    this.userRepository = userRepository;
    this.tradeAuditRepository = tradeAuditRepository;
    this.guard = guard;
    this.effects = effects;
    this.clock = clock;
    this.historyWriter = historyWriter;
    this.events = events;
  }

  /** 제품 소유자가 DIRECT 이전을 발급한다. 제품 잠금 아래 INTERNAL 예약을 보존하며 같은 트랜잭션에 감사한다. */
  @Transactional
  public CreateTransferResponse createTransfer(long actorId, CreateTransferRequest req) {
    if (req.productId() == null || req.productId() < 1 || req.purchaseRequestId() != null) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    if (req.toUserId() != null) {
      if (req.toUserId() < 1 || Objects.equals(req.toUserId(), actorId)) {
        throw new CommonException(ErrorCode.INVALID_INPUT);
      }
      if (!userRepository.existsById(req.toUserId())) {
        throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
      }
    }
    Long ownerId =
        productRepository
            .findOwnerId(req.productId())
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));

    if (!Objects.equals(ownerId, actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

    // 거래 JDBC 경로와 같은 제품→이전 순서로 잠가 예약 슬롯의 확인과 변경을 직렬화한다.
    if (!productRepository.lockOwned(req.productId(), actorId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    boolean saleInProgress = guard.lockSales(req.productId());

    // 만료된 대기 요청이 새 이전 요청을 영영 막지 않도록 먼저 만료 처리한다.
    Instant now = clock.instant();
    transferRepository
        .findByProductIdAndStatus(req.productId(), TransferStatus.PENDING)
        .ifPresent(
            pending -> {
              if (pending.getTransferType() != TransferType.DIRECT || !pending.isExpired(now)) {
                throw new CommonException(ErrorCode.OWNERSHIP_ALREADY_PENDING);
              }
              pending.expire();
              transferRepository.saveAndFlush(pending);
            });

    if (saleInProgress) throw new CommonException(ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);

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
            null,
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
   * 원문 토큰의 DIRECT 이전만 읽기 전용으로 검증하며 INTERNAL 식별자는 노출하지 않는다. 로그인한 사용자에게만 보여 주고, 수신자가 지정된 요청은 수락 시점이
   * 아니라 조회 시점에도 지정 수신자(와 발급자)만 볼 수 있어 토큰을 얻은 다른 사용자에게 제품명·판매자 정보가 노출되지 않는다.
   */
  public VerifyTransferResponse verifyTransfer(long actorId, String rawToken) {
    String tokenHash = hashToken(rawToken);
    OwnershipTransfer transfer =
        transferRepository
            .findByTokenHash(tokenHash)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

    requireDirect(transfer);

    if (transfer.getToUserId() != null
        && !Objects.equals(transfer.getToUserId(), actorId)
        && !Objects.equals(transfer.getFromUserId(), actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

    Instant now = clock.instant();
    boolean isExpired = transfer.isExpired(now);
    boolean isPending = transfer.getStatus() == TransferStatus.PENDING;
    boolean canAccept =
        isPending
            && !isExpired
            && productRepository
                .findOwnerId(transfer.getProductId())
                .filter(owner -> owner.equals(transfer.getFromUserId()))
                .isPresent()
            && !guard.hasSale(transfer.getProductId());

    String productTitle =
        canAccept ? productRepository.findTitle(transfer.getProductId()).orElse("등록된 제품") : null;
    String fromUserNickname =
        !canAccept
            ? null
            : userRepository
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

  /**
   * 인증된 수신자가 DIRECT를 명시 수락한다. 제품→판매글→이전 잠금 뒤 권한·만료를 재검증하고 소유자/facts·TRANSFER·증빙·판매자 초안·감사를 같은
   * 트랜잭션에서 확정한다. 실패는 JPA flush와 JDBC 효과를 모두 롤백한다.
   */
  @Transactional
  public AcceptTransferResponse acceptTransfer(long actorId, AcceptTransferRequest req) {
    String tokenHash = hashToken(req.token());
    long productId =
        transferRepository
            .findProductIdByTokenHash(tokenHash)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));
    lockProduct(productId);
    boolean saleInProgress = guard.lockSales(productId);
    OwnershipTransfer transfer =
        transferRepository
            .findByTokenHashForUpdate(tokenHash)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

    if (transfer.getTransferType() != TransferType.DIRECT) {
      throw new CommonException(ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    }

    Instant now = clock.instant();
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

    if (saleInProgress) throw new CommonException(ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    if (!userRepository.existsById(actorId)) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    productRepository.updateOwner(productId, actorId);
    transfer.complete(actorId, now);
    transferRepository.flush();

    // 소유자 변경과 같은 트랜잭션에서 개인정보 없는 시스템 이력을 남기고 누적 공개를 종료한다.
    historyWriter.recordCompletedTransfer(
        new InternalTransferHistoryWriter.Command(
            transfer.getProductId(), transfer.getId(), transfer.getFromUserId(), actorId, now));
    effects.protectDocuments(productId, now);

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
    events.publishEvent(new ProductFactsChanged(productId));

    return new AcceptTransferResponse(
        transfer.getId(), transfer.getProductId(), actorId, transfer.getCompletedAt());
  }

  /** 발신자만 DIRECT 대기 이전 ID를 취소하며, 제품→이전 잠금 안에서 슬롯과 감사를 함께 갱신한다. */
  @Transactional
  public void cancelTransfer(long actorId, Long transferId) {
    long productId =
        transferRepository
            .findProductIdByTransferId(transferId)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));
    lockProduct(productId);
    OwnershipTransfer transfer =
        transferRepository
            .findByIdForUpdate(transferId)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));

    if (!Objects.equals(transfer.getFromUserId(), actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

    if (transfer.getTransferType() != TransferType.DIRECT) {
      throw new CommonException(ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    }

    if (transfer.getStatus() != TransferStatus.PENDING) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }

    if (!Objects.equals(productRepository.findOwnerId(productId).orElse(null), actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
    Instant now = clock.instant();
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

  /** 현재 소유자에게만 최근 DIRECT 상태를 반환한다. 토큰은 재조회하지 않으며 읽기에서 만료 쓰기를 하지 않는다. */
  public OwnerTransferResponse ownerStatus(long actorId, long productId) {
    long owner =
        productRepository
            .findOwnerId(productId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    if (owner != actorId) throw new CommonException(ErrorCode.ACCESS_DENIED);
    var transfer =
        transferRepository
            .findFirstByProductIdAndFromUserIdAndTransferTypeOrderByIdDesc(
                productId, actorId, TransferType.DIRECT)
            .orElseThrow(() -> new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND));
    return new OwnerTransferResponse(
        transfer.getId(),
        productId,
        transfer.getExpiresAt(),
        transfer.getStatus(),
        transfer.isExpired(clock.instant()));
  }

  private void requireDirect(OwnershipTransfer transfer) {
    if (transfer.getTransferType() != TransferType.DIRECT) {
      throw new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND);
    }
  }

  /** 제품→이전 잠금 순서를 지키고, 이전 엔티티는 잠금 이후 최초 로딩해 최신 상태를 검사한다. */
  private void lockProduct(long productId) {
    if (!productRepository.lockForTransfer(productId)) {
      throw new CommonException(ErrorCode.OWNERSHIP_NOT_FOUND);
    }
  }

  public static String hashToken(String rawToken) {
    if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not found", e);
    }
  }
}
