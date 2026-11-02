package org.example.springtestci.ownership.dto;

import java.time.Instant;

public record ApproveTransferResponse(long transferId, Instant completedAt) {}
