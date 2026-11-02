package org.example.springtestci.listing.demo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.example.springtestci.listing.service.ListingHistoryReader.ChangeAction;
import org.example.springtestci.listing.service.ListingHistoryReader.Correction;
import org.example.springtestci.listing.service.ListingHistoryReader.Event;
import org.example.springtestci.listing.service.ListingHistoryReader.EventStatus;
import org.example.springtestci.listing.service.ListingHistoryReader.EventType;
import org.example.springtestci.listing.service.ListingHistoryReader.PublicText;
import org.example.springtestci.listing.service.ListingHistoryReader.Snapshot;
import org.example.springtestci.listing.service.ListingHistoryReader.State;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** 로컬 샘플 전용 이력 공급자. 운영 이력이나 공개 상태를 생성·수정하지 않는다. */
@Configuration(proxyBeanMethods = false)
@Profile("sale-draft-demo")
public class ListingPublicationDemoConfig {

  @Bean
  ListingHistoryReader listingHistoryDemoReader() {
    return productId ->
        productId == 101
            ? new Snapshot(
                State.READY,
                true,
                List.of(
                    event(
                        501,
                        2L,
                        EventType.PURCHASE,
                        "2024-02-12",
                        EventStatus.VALID,
                        "제품 구매",
                        "제품의 첫 구매 기록입니다.",
                        List.of()),
                    event(
                        502,
                        2L,
                        EventType.TRANSFER,
                        "2024-06-15",
                        EventStatus.VALID,
                        "시스템 이전",
                        null,
                        List.of()),
                    event(
                        503,
                        1L,
                        EventType.INSPECTION,
                        "2025-05-20",
                        EventStatus.VALID,
                        "하드웨어 정기 점검",
                        "메모리와 저장장치 동작을 점검했습니다.",
                        List.of(
                            new Correction(
                                801,
                                Instant.parse("2025-05-22T08:00:00Z"),
                                "점검 발생일 오입력을 정정했습니다.",
                                true,
                                ChangeAction.HISTORY_CORRECTED))),
                    event(
                        504,
                        2L,
                        EventType.REPAIR,
                        "2025-04-02",
                        EventStatus.VOID,
                        "중복 수리 기록",
                        null,
                        List.of(
                            new Correction(
                                802,
                                Instant.parse("2025-04-03T08:00:00Z"),
                                "같은 작업이 중복 등록되어 무효 처리했습니다.",
                                true,
                                ChangeAction.HISTORY_VOIDED)))))
            : new Snapshot(State.READY, true, List.of());
  }

  private Event event(
      long id,
      Long authorId,
      EventType type,
      String date,
      EventStatus status,
      String title,
      String content,
      List<Correction> corrections) {
    return new Event(
        id,
        101,
        type == EventType.TRANSFER ? null : authorId,
        type,
        LocalDate.parse(date),
        Instant.parse(date + "T09:00:00Z"),
        status,
        new PublicText(title, content, null, null, true),
        corrections);
  }
}
