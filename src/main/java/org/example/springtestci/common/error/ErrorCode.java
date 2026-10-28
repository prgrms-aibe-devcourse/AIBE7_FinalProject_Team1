package org.example.springtestci.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "요청 값이 올바르지 않습니다."),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH_001", "인증이 필요합니다."),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_002", "접근 권한이 없습니다."),
  DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH_003", "이미 가입된 이메일입니다."),
  DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "AUTH_004", "이미 사용 중인 닉네임입니다."),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_005", "이메일 또는 비밀번호가 올바르지 않습니다."),
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_006", "다시 로그인해 주세요."),
  EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "AUTH_008", "이메일 인증을 완료해 주세요."),
  TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH_010", "잠시 후 다시 시도해 주세요."),
  LOGIN_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AUTH_012", "로그인 시도가 너무 많아요. 잠시 후 다시 시도해 주세요."),
  REQUEST_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "COMMON_003", "요청이 너무 많아요. 잠시 후 다시 시도해 주세요."),
  EMAIL_SEND_FAILED(
      HttpStatus.SERVICE_UNAVAILABLE, "AUTH_011", "인증 메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_002", "요청한 리소스를 찾을 수 없습니다."),
  DRAFT_CONFLICT(
      HttpStatus.CONFLICT, "SALE_DRAFT_001", "다른 저장본이 있거나 저장 후 내용이 변경되었습니다. 최신 저장본을 확인해 주세요."),
  DRAFT_PRODUCT_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "SALE_DRAFT_002", "제품 정보를 아직 불러올 수 없습니다. 잠시 후 다시 시도해 주세요."),
  LISTING_HISTORY_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "SALE_HISTORY_001", "공개 예정 이력을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."),
  LISTING_REQUIRED_FIELDS(
      HttpStatus.BAD_REQUEST, "SALE_001", "게시하려면 제목·가격·상태 등급·설명과 대표 사진을 입력해 주세요."),
  LISTING_ALREADY_OPEN(HttpStatus.CONFLICT, "SALE_002", "이미 판매 중이거나 예약 중인 판매글이 있습니다."),
  LISTING_PUBLISH_BLOCKED(HttpStatus.CONFLICT, "SALE_003", "진행 중인 거래나 소유권 이전 요청이 있어 게시할 수 없습니다."),
  LISTING_PUBLISH_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "SALE_004", "게시 조건·이력 공개 기능의 연동이 준비되지 않았습니다. 임시저장본은 유지됩니다."),
  LISTING_EDIT_CONFLICT(HttpStatus.CONFLICT, "SALE_005", "판매글이 변경되었습니다. 최신 내용을 불러온 뒤 다시 시도해 주세요."),
  LISTING_STATE_CONFLICT(
      HttpStatus.CONFLICT,
      "SALE_006",
      "현재 판매 상태에서는 처리할 수 없습니다. 판매 중인 글만 수정할 수 있고 종료된 글은 다시 공개할 수 없습니다."),
  LISTING_CANCEL_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "SALE_007",
      "거래·이전 취소와 이력 비공개 연동이 준비되지 않았습니다. 판매글은 변경되지 않습니다."),
  PRODUCT_IMAGE_IN_USE(
      HttpStatus.CONFLICT, "PRODUCT_005", "판매 중이거나 예약 중인 판매글의 사진은 먼저 판매글에서 제외해 주세요."),
  AI_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "AI_001", "AI 제안을 사용할 수 없습니다. 잠시 후 재시도하거나 직접 작성해 주세요."),
  AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI_002", "AI 응답 시간이 초과되었습니다. 재시도하거나 직접 작성해 주세요."),
  AI_INVALID_RESULT(HttpStatus.BAD_GATEWAY, "AI_003", "AI 제안이 검증을 통과하지 못했습니다. 재시도하거나 직접 작성해 주세요."),
  AI_SOURCE_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "AI_004",
      "전체 핵심 이력을 확인하지 못해 AI 제안을 만들 수 없습니다. 직접 작성은 가능합니다."),
  AI_SOURCE_CHANGED(HttpStatus.CONFLICT, "AI_005", "생성 중 제품 정보나 이력이 변경되었습니다. 최신 정보로 다시 생성해 주세요."),
  AI_INPUT_TOO_LARGE(
      HttpStatus.UNPROCESSABLE_ENTITY,
      "AI_006",
      "전체 제품·이력이 AI 입력 한도를 초과했습니다. 이력을 생략하지 않으므로 직접 작성해 주세요."),
  AI_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AI_007", "AI 요청이 많습니다. 잠시 후 재시도하거나 직접 작성해 주세요."),
  SUMMARY_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "AI_SUMMARY_001",
      "AI 요약 기능을 현재 사용할 수 없습니다. 제품 정보와 이력은 계속 확인할 수 있습니다."),
  SUMMARY_RETRY_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "AI_SUMMARY_002", "AI 요약을 잠시 후 다시 요청해 주세요."),
  INVALID_EXTRACTION_STATE(HttpStatus.CONFLICT, "DOC_001", "현재 상태에서는 처리할 수 없는 추출 요청입니다."),
  INVALID_DOCUMENT_FILE(
      HttpStatus.BAD_REQUEST, "DOC_002", "JPG, PNG, PDF 형식의 올바른 파일만 업로드할 수 있습니다."),
  DOCUMENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "DOC_003", "파일은 최대 10MB까지 업로드할 수 있습니다."),
  MASKING_NOT_CONFIRMED(HttpStatus.BAD_REQUEST, "DOC_004", "개인정보 마스킹을 확인한 사본만 저장할 수 있습니다."),
  DOCUMENT_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "DOC_005", "제품당 업로드할 수 있는 문서 수를 초과했습니다."),
  DOCUMENT_DELETE_NOT_ALLOWED(HttpStatus.CONFLICT, "DOC_006", "이력에 연결되었거나 이전받은 증빙은 삭제할 수 없습니다."),
  DOCUMENT_REDACTION_REQUIRED(
      HttpStatus.CONFLICT, "DOC_007", "개인정보 재마스킹이 필요한 증빙은 교체하기 전까지 열람할 수 없습니다."),
  DOCUMENT_STORAGE_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "DOC_008", "파일 저장소를 아직 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."),
  PRODUCT_DUPLICATE_SERIAL(
      HttpStatus.CONFLICT, "PRODUCT_001", "이미 등록된 제품입니다. 현재소유자에게 소유권 이전을 요청하세요."),
  INVALID_PRODUCT_IMAGE(
      HttpStatus.BAD_REQUEST, "PRODUCT_002", "JPG, PNG, WEBP 형식의 올바른 이미지만 업로드할 수 있습니다."),
  PRODUCT_IMAGE_TOO_LARGE(
      HttpStatus.CONTENT_TOO_LARGE, "PRODUCT_003", "이미지는 최대 5MB까지 업로드할 수 있습니다."),
  PRODUCT_IMAGE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "PRODUCT_004", "제품당 이미지는 최대 10장까지 등록할 수 있습니다."),
  INVALID_HISTORY_EVENT(HttpStatus.BAD_REQUEST, "HISTORY_001", "제품 이력 내용이 올바르지 않습니다."),
  HISTORY_EVENT_CHANGE_NOT_ALLOWED(
      HttpStatus.FORBIDDEN, "HISTORY_002", "본인이 작성한 일반 이력만 정정하거나 무효 처리할 수 있습니다."),
  INVALID_HISTORY_DOCUMENT(
      HttpStatus.BAD_REQUEST, "HISTORY_003", "같은 제품의 마스킹 확인된 증빙만 이력에 연결할 수 있습니다."),
  TRADE_CONFLICT(HttpStatus.CONFLICT, "TRADE_001", "구매 요청을 현재 상태에서 처리할 수 없습니다."),
  TRADE_DEPENDENCY_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "TRADE_002", "거래 연동 기능을 일시적으로 사용할 수 없습니다."),
  OWNERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "OWN_001", "소유권 이전 요청을 찾을 수 없습니다."),
  OWNERSHIP_EXPIRED(HttpStatus.GONE, "OWN_002", "만료되었거나 유효하지 않은 소유권 이전 요청입니다."),
  OWNERSHIP_ALREADY_PENDING(HttpStatus.CONFLICT, "OWN_003", "이미 진행 중인 소유권 이전 요청이 있습니다."),
  OWNERSHIP_SELF_ACCEPT(HttpStatus.BAD_REQUEST, "OWN_004", "자신의 제품 소유권은 직접 수락할 수 없습니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;

  /** HTTP 상태, 오류 코드, 사용자에게 보여줄 메시지로 오류를 정의한다. */
  ErrorCode(HttpStatus status, String code, String message) {
    this.status = status;
    this.code = code;
    this.message = message;
  }

  /** 응답에 쓸 HTTP 상태를 돌려준다. */
  public HttpStatus status() {
    return status;
  }

  /** 클라이언트가 구분하는 오류 코드를 돌려준다. */
  public String code() {
    return code;
  }

  /** 사용자에게 보여줄 메시지를 돌려준다. */
  public String message() {
    return message;
  }
}
