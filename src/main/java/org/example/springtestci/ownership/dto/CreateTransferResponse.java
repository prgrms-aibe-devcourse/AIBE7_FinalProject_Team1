package org.example.springtestci.ownership.dto;

import java.time.Instant;

public record CreateTransferResponse(
    long transferId, long productId, String rawToken, Instant expiresAt, String linkUrl) {}
