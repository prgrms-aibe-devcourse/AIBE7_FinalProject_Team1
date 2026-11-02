package org.example.springtestci.admin.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.format.annotation.DateTimeFormat;

/** 관리자에게 허용된 업무 정보만 반환한다. 원본 문서·채팅·인증 정보는 응답에 포함하지 않는다. */
public final class AdminDtos {
  private AdminDtos() {}

  /** from/to는 한국 시간의 달력 날짜이며 종료일을 포함한다. 저장된 timestamp의 시간대는 바꾸지 않는다. */
  public record Filter(
      Integer page,
      Integer size,
      Long productId,
      Long actorId,
      String action,
      String status,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      String productQuery,
      String memberQuery,
      String cancelReason) {
    public Filter {
      page = page == null ? 0 : page;
      size = size == null ? 20 : size;
      action = action == null || action.isBlank() ? null : action.trim();
      status = status == null || status.isBlank() ? null : status.trim();
      productQuery = productQuery == null || productQuery.isBlank() ? null : productQuery.trim();
      memberQuery = memberQuery == null || memberQuery.isBlank() ? null : memberQuery.trim();
      cancelReason = cancelReason == null || cancelReason.isBlank() ? null : cancelReason.trim();
    }

    public void validate() {
      if (page < 0
          || page > 100_000
          || size < 1
          || size > 100
          || (productId != null && productId < 1)
          || (actorId != null && actorId < 1)
          || (action != null && !action.matches("[A-Z_]{1,50}"))
          || (status != null
              && !List.of("PENDING", "REJECTED", "RESERVED", "COMPLETED", "CANCELLED")
                  .contains(status))
          || (from != null && to != null && from.isAfter(to))
          || (to != null && to.getYear() >= 9999)
          || (productQuery != null && productQuery.length() > 200)
          || (memberQuery != null && memberQuery.length() > 254)
          || (cancelReason != null && status != null && !status.equals("CANCELLED"))) {
        throw new CommonException(ErrorCode.INVALID_INPUT);
      }
      if (cancelReason != null) {
        try {
          CancellationCategory.valueOf(cancelReason);
        } catch (IllegalArgumentException invalid) {
          throw new CommonException(ErrorCode.INVALID_INPUT);
        }
      }
    }

    public int offset() {
      return page * size;
    }
  }

  public record Page<T>(List<T> items, int page, int size, long total) {}

  /** 목록과 요약은 한 읽기 트랜잭션의 같은 검색 범위에서 계산한다. 기존 페이지 필드는 유지한다. */
  public record TradePage(
      List<Trade> items, int page, int size, long total, TradeOverview overview) {}

  public enum CancellationCategory {
    SELF_CIRCUMSTANCES,
    MUTUAL_AGREEMENT,
    COUNTERPART_UNREACHABLE,
    CONDITION_MISMATCH,
    BEFORE_RESERVATION,
    UNRECORDED
  }

  public record CancellationCount(CancellationCategory category, long count) {}

  /** 상태·취소 유형 선택 전의 전체 검색 범위다. total에는 예약 전 요청·거절도 포함한다. */
  public record TradeOverview(
      long total,
      long pending,
      long rejected,
      long reserved,
      long completed,
      long cancelled,
      long other,
      List<CancellationCount> cancellations) {}

  /** 현재 등록 정보다. 거래 당시의 명칭·이메일 사본을 의미하지 않는다. */
  public record ProductSummary(long id, String title, String manufacturer, String modelName) {}

  public record MemberSummary(long id, String nickname, String email) {}

  public record Trade(
      long id,
      long productId,
      long listingId,
      long sellerId,
      long buyerId,
      String requestStatus,
      String transactionStatus,
      OffsetDateTime reservedAt,
      OffsetDateTime completedAt,
      OffsetDateTime cancelledAt,
      Long cancelledById,
      String cancelReason,
      ProductSummary product,
      String listingTitle,
      MemberSummary seller,
      MemberSummary buyer,
      MemberSummary cancelledBy) {}

  public record Audit(
      long id,
      long productId,
      Long actorId,
      long targetId,
      Long eventId,
      String action,
      String result,
      OffsetDateTime createdAt,
      ProductSummary product,
      MemberSummary actor) {}

  public record Facts(Map<String, Object> values, boolean available) {}

  public record AuditDetail(Audit record, String reason, Facts before, Facts after) {}

  /** 저장된 시각과 연결 식별자로 확인한 사건이다. 처리 주체가 미기록이면 시스템으로 추측하지 않는다. */
  public record TimelineEntry(
      String key,
      String action,
      String source,
      OffsetDateTime occurredAt,
      String actorType,
      MemberSummary actor,
      String reason,
      Long eventId,
      Long auditId) {}

  public record TradeTimeline(
      long tradeId, Page<TimelineEntry> events, boolean reviewHistoryAvailable) {}

  public enum ProgressState {
    WAITING_SELLER_ACCEPTANCE,
    WAITING_BUYER_CHANGE_REVIEW,
    WAITING_BOTH_CONFIRMATIONS,
    WAITING_SELLER_CONFIRMATION,
    WAITING_BUYER_CONFIRMATION,
    CONFIRMATIONS_RECEIVED,
    COMPLETED,
    CANCELLED,
    REJECTED,
    CONFIRMATION_UNAVAILABLE,
    CONNECTION_CHECK_REQUIRED
  }

  public enum ConfirmationState {
    NOT_CONFIRMED,
    CURRENT,
    RECONFIRM_REQUIRED,
    NOT_APPLICABLE,
    UNAVAILABLE
  }

  /** 현재 스냅샷의 확인 상태다. 거래 완료 허가나 확인 시각을 의미하지 않는다. */
  public record TradeProgress(
      long tradeId,
      ProgressState state,
      ConfirmationState sellerConfirmation,
      ConfirmationState buyerConfirmation,
      Long unreviewedChangeCount,
      Long factsVersion,
      Long sellerConfirmedVersion,
      Long buyerConfirmedVersion) {}
}
