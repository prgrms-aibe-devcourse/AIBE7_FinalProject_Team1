package org.example.springtestci.ownership.dto;

import java.time.Instant;
import org.example.springtestci.ownership.entity.TransferStatus;

/** 현재 소유자의 새로고침 복구용 상태이며 raw token이나 해시는 포함하지 않는다. */
public record OwnerTransferResponse(
    long transferId,
    long productId,
    Instant expiresAt,
    TransferStatus status,
    boolean expired,
    Long recipientId,
    String recipientNickname,
    Instant recipientAcceptedAt,
    String productTitle,
    long factsVersion) {}
