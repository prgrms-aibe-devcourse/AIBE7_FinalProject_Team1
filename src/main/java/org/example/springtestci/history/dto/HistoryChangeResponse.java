package org.example.springtestci.history.dto;

import java.time.OffsetDateTime;

/** 제품 이력에 차례로 누적된 정정·무효 처리 사유와 처리 시각을 반환한다. */
public record HistoryChangeResponse(String action, String reason, OffsetDateTime changedAt) {}
