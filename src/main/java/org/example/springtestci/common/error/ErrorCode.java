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
  INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "AUTH_007", "비밀번호가 올바르지 않습니다."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_002", "요청한 리소스를 찾을 수 없습니다."),
  DRAFT_CONFLICT(
      HttpStatus.CONFLICT, "SALE_DRAFT_001", "다른 저장본이 있거나 저장 후 내용이 변경되었습니다. 최신 저장본을 확인해 주세요."),
  DRAFT_PRODUCT_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "SALE_DRAFT_002", "제품 정보를 아직 불러올 수 없습니다. 잠시 후 다시 시도해 주세요."),
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
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_999", "서버 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String code;
  private final String message;

  /** HTTP 상태, 애플리케이션 오류 코드, 사용자 메시지로 오류 항목을 구성한다. */
  ErrorCode(HttpStatus status, String code, String message) {
    this.status = status;
    this.code = code;
    this.message = message;
  }

  /** 오류에 대응하는 HTTP 상태를 반환한다. */
  public HttpStatus status() {
    return status;
  }

  /** 클라이언트가 식별할 수 있는 애플리케이션 오류 코드를 반환한다. */
  public String code() {
    return code;
  }

  /** 클라이언트에 표시할 기본 오류 메시지를 반환한다. */
  public String message() {
    return message;
  }
}
