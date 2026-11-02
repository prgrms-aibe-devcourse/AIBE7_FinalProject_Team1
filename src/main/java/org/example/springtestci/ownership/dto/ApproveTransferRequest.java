package org.example.springtestci.ownership.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record ApproveTransferRequest(
    @NotNull @Positive Long expectedRecipientId,
    @NotNull @PositiveOrZero Long expectedFactsVersion) {}
