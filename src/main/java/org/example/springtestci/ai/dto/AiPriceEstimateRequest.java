package org.example.springtestci.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record AiPriceEstimateRequest(
    @NotBlank String modelName,
    String cpuSpec,
    Integer ramGb,
    Integer storageGb,
    @PositiveOrZero Integer batteryHealthPercent) {}
