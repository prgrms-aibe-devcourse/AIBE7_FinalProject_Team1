package org.example.springtestci.ownership.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "ownership_transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OwnershipTransfer {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "from_user_id", nullable = false)
  private Long fromUserId;

  @Column(name = "to_user_id")
  private Long toUserId;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private TransferStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "transfer_type", nullable = false, length = 20)
  private TransferType transferType;

  @Column(name = "purchase_request_id")
  private Long purchaseRequestId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** 대기 중일 때만 1, 그 밖의 상태는 null. (product_id, pending_slot) 유니크로 제품당 대기 요청을 하나로 제한한다. */
  @Column(name = "pending_slot")
  private Short pendingSlot;

  public static OwnershipTransfer create(
      Long productId,
      Long fromUserId,
      Long toUserId,
      String tokenHash,
      TransferType transferType,
      Long purchaseRequestId,
      Instant expiresAt) {
    OwnershipTransfer transfer = new OwnershipTransfer();
    transfer.productId = productId;
    transfer.fromUserId = fromUserId;
    transfer.toUserId = toUserId;
    transfer.tokenHash = tokenHash;
    transfer.transferType = transferType != null ? transferType : TransferType.DIRECT;
    transfer.purchaseRequestId = purchaseRequestId;
    transfer.status = TransferStatus.PENDING;
    transfer.pendingSlot = 1;
    transfer.expiresAt = expiresAt;
    transfer.createdAt = Instant.now();
    return transfer;
  }

  public void complete(Long receiverId, Instant now) {
    this.status = TransferStatus.COMPLETED;
    this.pendingSlot = null;
    this.toUserId = receiverId;
    this.completedAt = now;
  }

  public void cancel(Instant now) {
    this.status = TransferStatus.CANCELLED;
    this.pendingSlot = null;
    this.cancelledAt = now;
  }

  public void expire() {
    this.status = TransferStatus.EXPIRED;
    this.pendingSlot = null;
  }

  public boolean isExpired(Instant now) {
    return now.isAfter(expiresAt);
  }
}
