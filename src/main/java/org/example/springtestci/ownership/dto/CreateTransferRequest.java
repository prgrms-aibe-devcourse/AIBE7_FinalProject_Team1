package org.example.springtestci.ownership.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateTransferRequest(
    @NotNull @Positive Long productId, Long toUserId, Long purchaseRequestId) {}
