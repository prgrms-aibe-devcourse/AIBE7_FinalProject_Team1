package org.example.springtestci.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.dto.ListingPublicationResponse;
import org.example.springtestci.listing.dto.ListingPublicationResponse.DetailsStatus;
import org.example.springtestci.listing.service.ListingHistoryReader.EventStatus;
import org.example.springtestci.listing.service.ListingHistoryReader.State;
import org.example.springtestci.listing.service.ListingPublicationService;
import org.example.springtestci.product.service.ProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 제목·본문 제안과 상태 요약에 공통으로 쓸 공개 제품·전체 이력 JSON을 만든다. 자료 구성 중에만 제품을 잠근다. */
@Service
public class ListingSuggestionSource {
  // 한도를 넘으면 일부 이력을 임의로 빼지 않고 요청을 거부한다.
  static final int MAX_INPUT_BYTES = 48_000;
  private final ProductService products;
  private final ListingPublicationService publications;
  private final ObjectMapper json;

  public ListingSuggestionSource(
      ProductService products, ListingPublicationService publications, ObjectMapper json) {
    this.products = products;
    this.publications = publications;
    this.json = json;
  }

  @Transactional
  public Snapshot capture(long productId, long userId) {
    var publication = publications.getForAi(productId, userId);
    var owned = products.getForListing(userId, productId);
    if (publication.history().status() != State.READY)
      throw new CommonException(ErrorCode.AI_SOURCE_UNAVAILABLE);
    // 자동 검사 후 공개 가능한 유효 상세만 사용한다. 무효·상세 제외 이력도 존재·날짜·상태는 남긴다.
    var events = publication.history().items().stream().map(AiEvent::from).toList();
    var data = new Input(publication.product(), events);
    String source = json.writeValueAsString(data);
    if (source.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES)
      throw new CommonException(ErrorCode.AI_INPUT_TOO_LARGE);
    // 제품 버전과 공개 자료를 하나의 지문으로 만들어 AI 생성 전후 이력 변경까지 비교한다.
    String fingerprint = digest(owned.factsVersion() + ":" + source);
    return new Snapshot(owned.factsVersion(), fingerprint, source, publication.history().sample());
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public record Snapshot(long factsVersion, String sourceVersion, String json, boolean sample) {}

  private record Input(ListingPublicationResponse.Product product, List<AiEvent> history) {}

  private record AiEvent(
      long eventId,
      String type,
      String occurredOn,
      String status,
      boolean previousOwner,
      boolean corrected,
      boolean detailsReviewed,
      String title,
      String content,
      String replacedPart) {
    static AiEvent from(ListingPublicationResponse.Event event) {
      boolean usable =
          event.status() == EventStatus.VALID && event.detailsStatus() == DetailsStatus.REVIEWED;
      return new AiEvent(
          event.eventId(),
          event.eventType().name(),
          event.occurredOn().toString(),
          event.status().name(),
          event.previousOwner(),
          !event.corrections().isEmpty(),
          event.detailsStatus() == DetailsStatus.REVIEWED,
          usable ? event.title() : null,
          usable ? event.content() : null,
          usable ? event.replacedPart() : null);
    }
  }
}
