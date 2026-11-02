package org.example.springtestci.ownership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptTransferRequest(@NotBlank @Size(max = 256) String token) {}
