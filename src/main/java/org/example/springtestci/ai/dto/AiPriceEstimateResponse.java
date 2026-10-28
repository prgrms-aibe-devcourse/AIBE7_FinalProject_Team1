package org.example.springtestci.ai.dto;

public record AiPriceEstimateResponse(
    long estimatedMinPrice, long estimatedMaxPrice, long recommendedPrice, String reasoning) {}
