package org.example.springtestci.product.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 일련번호는 현재 소유자의 상세 응답에만 채우고 목록 응답에서는 null로 둔다. */
public record ProductResponse(
    long productId,
    String title,
    String manufacturer,
    String modelName,
    LocalDate purchaseDate,
    LocalDate warrantyExpiresAt,
    String warrantyTerms,
    long factsVersion,
    String serialNumber,
    String warrantyStatus,
    Specifications specifications,
    List<Image> images,
    OffsetDateTime createdAt) {

  /** 제품 상세 화면에 표시할 노트북 하드웨어 사양을 나타낸다. */
  public record Specifications(
      String cpu,
      String gpu,
      Integer ramGb,
      Integer storageGb,
      String storageType,
      String display,
      Integer batteryHealthPercent,
      String os) {}

  /** 제품 이미지의 식별자, 접근 URL, 정렬 정보를 나타낸다. */
  public record Image(long imageId, String url, boolean primary, int sortOrder, long byteSize) {}

  /** 현재 응답의 제품 정보는 유지하고 이미지 목록만 교체한다. */
  public ProductResponse withImages(List<Image> images) {
    return new ProductResponse(
        productId,
        title,
        manufacturer,
        modelName,
        purchaseDate,
        warrantyExpiresAt,
        warrantyTerms,
        factsVersion,
        serialNumber,
        warrantyStatus,
        specifications,
        images,
        createdAt);
  }

  /** 현재 응답의 제품 정보는 유지하고 일련번호만 교체한다. */
  public ProductResponse withSerialNumber(String serialNumber) {
    return new ProductResponse(
        productId,
        title,
        manufacturer,
        modelName,
        purchaseDate,
        warrantyExpiresAt,
        warrantyTerms,
        factsVersion,
        serialNumber,
        warrantyStatus,
        specifications,
        images,
        createdAt);
  }
}
