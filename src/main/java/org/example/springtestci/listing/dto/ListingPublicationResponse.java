package org.example.springtestci.listing.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.example.springtestci.listing.service.ListingHistoryReader.ChangeAction;
import org.example.springtestci.listing.service.ListingHistoryReader.EventStatus;
import org.example.springtestci.listing.service.ListingHistoryReader.EventType;
import org.example.springtestci.listing.service.ListingHistoryReader.State;

/** R03 공개 예정 정보. 현재 소유자에게만 제공하며 조회로 저장·게시·이력 공개를 수행하지 않는다. */
public record ListingPublicationResponse(Product product, History history) {

  /** 사적인 제품 별칭·시리얼·소유자·비용·문서·파일 경로를 포함하지 않는 명시적 허용 목록. */
  public record Product(
      long productId,
      String manufacturer,
      String modelName,
      LocalDate purchaseDate,
      LocalDate warrantyExpiresAt,
      DraftProductResponse.Specifications specifications) {}

  public record History(
      State status, boolean sample, List<Event> items, Page page, long validCount, long voidCount) {
    public History {
      items = List.copyOf(items);
    }
  }

  public record Page(int number, int size, long totalElements, int totalPages, boolean hasNext) {}

  public enum DetailsStatus {
    // REVIEWED는 자동 공개 검사 완료를 뜻한다. ADMIN 승인 여부가 아니다.
    REVIEWED,
    REVIEW_PENDING,
    EXCLUDED
  }

  public record Event(
      long eventId,
      EventType eventType,
      LocalDate occurredOn,
      Instant createdAt,
      EventStatus status,
      String title,
      String content,
      String serviceProvider,
      String replacedPart,
      boolean previousOwner,
      boolean systemGenerated,
      DetailsStatus detailsStatus,
      List<Correction> corrections) {
    public Event {
      corrections = List.copyOf(corrections);
    }
  }

  public record Correction(
      long correctionId,
      Instant correctedAt,
      String reason,
      DetailsStatus detailsStatus,
      ChangeAction action) {}
}
