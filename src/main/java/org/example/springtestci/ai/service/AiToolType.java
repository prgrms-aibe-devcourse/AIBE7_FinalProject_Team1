package org.example.springtestci.ai.service;

public enum AiToolType {
  // SEC-06: 허용된 조회 도구만 제공
  GET_PRODUCT_SPEC(true, "제품 사양 조회"),
  GET_PUBLIC_HISTORY(true, "공개 이력 조회"),
  SEARCH_MARKET_PRICE(true, "시세 정보 조회"),

  // SEC-06: 게시·수정·삭제·공개 변경·소유권 이전은 엄격히 금지
  TRANSFER_OWNERSHIP(false, "소유권 이전 (금지)"),
  PUBLISH_LISTING(false, "판매글 게시 (금지)"),
  DELETE_DOCUMENT(false, "문서 삭제 (금지)"),
  UPDATE_PRODUCT(false, "제품 수정 (금지)");

  private final boolean allowed;
  private final String description;

  AiToolType(boolean allowed, String description) {
    this.allowed = allowed;
    this.description = description;
  }

  public boolean isAllowed() {
    return allowed;
  }

  public String getDescription() {
    return description;
  }
}
