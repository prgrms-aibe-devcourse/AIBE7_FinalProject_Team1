package org.example.springtestci.ai.entity;

import java.time.Instant;
import java.util.UUID;

/** listings의 요약 전용 열. JPA 판매글 변경이 요약 상태를 덮어쓰지 않도록 JDBC로 따로 관리한다. */
public record ListingSummaryState(
    Status status,
    String text,
    Long productVersion,
    Long listingVersion,
    // 생성 시도마다 바뀌는 번호다. 이전 시도의 늦은 응답이 새 결과를 덮어쓰지 못하게 한다.
    UUID attemptId,
    String errorCode,
    Instant updatedAt,
    int formatVersion) {
  // 공개 이력 본문을 입력에 포함하는 정책이다. 이전 메타데이터 전용 요약은 다시 생성한다.
  public static final int CARD_FORMAT_VERSION = 2;
  public static final int CARD_MAX_LENGTH = 90;

  /** 미요청 → 대기 → 처리 중 → 완료/실패를 구분해 화면 안내와 재시도 가능 여부를 판단한다. */
  public enum Status {
    NOT_REQUESTED,
    QUEUED,
    PROCESSING,
    READY,
    FAILED
  }
}
