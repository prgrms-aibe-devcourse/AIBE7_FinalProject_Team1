package org.example.springtestci.ownership.dto;

import java.time.Instant;

public record AcceptTransferResponse(
    long transferId, long productId, long newOwnerId, Instant completedAt) {}
