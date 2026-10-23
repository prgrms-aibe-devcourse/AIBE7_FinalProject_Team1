package org.example.springtestci.listing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CancelListingRequest(
    @NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 500) String reason) {
  public CancelListingRequest {
    reason = reason == null ? null : reason.strip();
  }
}
