package org.example.springtestci.ownership.dto;

import java.time.Instant;
import org.example.springtestci.ownership.entity.TransferStatus;

public record VerifyTransferResponse(
    Long transferId,
    Long productId,
    String productTitle,
    Long fromUserId,
    String fromUserNickname,
    Instant expiresAt,
    TransferStatus status,
    boolean isExpired,
    boolean canAccept,
    Instant recipientAcceptedAt) {}
