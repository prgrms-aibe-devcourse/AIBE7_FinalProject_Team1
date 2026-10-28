package org.example.springtestci.ownership.dto;

import java.time.Instant;
import org.example.springtestci.ownership.entity.TransferStatus;

public record VerifyTransferResponse(
    long transferId,
    long productId,
    String productTitle,
    long fromUserId,
    String fromUserNickname,
    Instant expiresAt,
    TransferStatus status,
    boolean isExpired,
    boolean canAccept) {}
