package org.example.springtestci.listing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 게시된 판매 조건 전체를 수정한다. 제품 정보·이력·상태는 이 요청으로 바꾸지 않는다. */
public record UpdateListingRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 60) String title,
    @NotBlank @Size(max = 2000) String description,
    @NotNull @DecimalMin("1") @Digits(integer = 12, fraction = 0) BigDecimal price,
    @NotNull @Min(1) @Max(5) Integer conditionGrade,
    @NotEmpty @Size(max = 20) List<@NotNull @Positive Long> selectedImageIds) {
  public UpdateListingRequest {
    title = title == null ? null : title.strip();
    description = description == null ? null : description.strip();
  }
}
