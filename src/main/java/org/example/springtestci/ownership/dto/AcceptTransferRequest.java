package org.example.springtestci.ownership.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptTransferRequest(@NotBlank String token) {}
