package org.example.springtestci.ownership.dto;

import java.time.Instant;
import org.example.springtestci.ownership.entity.TransferStatus;

public record TransferStatusResponse(
    long transferId,
    TransferStatus status,
    Instant recipientAcceptedAt,
    Instant completedAt,
    Instant expiresAt,
    boolean expired) {}
