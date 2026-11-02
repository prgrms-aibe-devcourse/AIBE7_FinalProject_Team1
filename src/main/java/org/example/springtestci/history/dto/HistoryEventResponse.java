package org.example.springtestci.history.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.example.springtestci.history.entity.HistoryEventStatus;
import org.example.springtestci.history.entity.HistoryEventType;

public record HistoryEventResponse(
    long eventId,
    long productId,
    HistoryEventType eventType,
    LocalDate occurredOn,
    String title,
    String content,
    String serviceProvider,
    Long cost,
    String replacedPart,
    Map<String, Object> specsBefore,
    Map<String, Object> specsAfter,
    HistoryEventStatus status,
    HistoryEventSource source,
    boolean editable,
    List<Long> documentIds,
    List<HistoryChangeResponse> changes,
    String changeAction,
    String changeReason,
    OffsetDateTime changedAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
