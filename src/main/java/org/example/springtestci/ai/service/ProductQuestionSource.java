package org.example.springtestci.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.repository.ListingRepository;
import org.example.springtestci.product.service.ProductService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 소유자 제품 또는 공개 판매글의 허용 자료만 구성한다. 외부 AI 대기 중에는 DB 잠금을 유지하지 않는다. */
@Service
public class ProductQuestionSource {
  private final ListingSuggestionSource sources;
  private final ListingRepository listings;
  private final ProductService products;
  private final ObjectMapper json;
  private final Clock clock;

  public ProductQuestionSource(
      ListingSuggestionSource sources,
      ListingRepository listings,
      ProductService products,
      ObjectMapper json,
      Clock clock) {
    this.sources = sources;
    this.listings = listings;
    this.products = products;
    this.json = json;
    this.clock = clock;
  }

  /** 제품→판매글 순서로 잠그고 현재 소유권·공개 상태를 확인한다. 제품 질문은 현재 소유자만 허용한다. */
  @Transactional
  public Snapshot capture(Target target, long userId) {
    if (userId <= 0) throw new CommonException(ErrorCode.UNAUTHORIZED);
    if (target == null || target.id() <= 0) throw new CommonException(ErrorCode.INVALID_INPUT);
    long productId = target.id(), ownerId = userId;
    Long contentVersion = null;
    String listingState = "OWNER";
    if (target.listing()) {
      var access =
          listings
              .findAccessById(target.id())
              .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
      productId = access.getProductId();
      ownerId = access.getSellerId();
      products.lockOwnedForListing(ownerId, productId);
      var listing =
          listings
              .findLockedById(target.id())
              .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
      if (!listing.getStatus().isPubliclyReadable())
        throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
      contentVersion = listing.getContentVersion();
      listingState = listing.getStatus().name();
    }
    // 소유자 질문도 같은 공개 필드 자동 검사를 사용한다. 시리얼·금액·증빙·제외된 본문은 넘기지 않는다.
    var source = sources.capture(productId, ownerId);
    LocalDate asOf = LocalDate.now(clock);
    String fingerprint =
        digest(
            target
                + ":"
                + listingState
                + ":"
                + contentVersion
                + ":"
                + asOf
                + ":"
                + source.sourceVersion());
    return new Snapshot(
        productId,
        source.factsVersion(),
        contentVersion,
        fingerprint,
        json.readTree(source.json()),
        asOf,
        source.sample());
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public record Target(boolean listing, long id) {}

  public record Snapshot(
      long productId,
      long factsVersion,
      Long contentVersion,
      String fingerprint,
      JsonNode data,
      LocalDate asOf,
      boolean sample) {}
}
