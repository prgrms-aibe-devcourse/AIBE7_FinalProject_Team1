package org.example.springtestci.auth.dto;

/** 가입 결과. 인증하지 않으면 계정이 정리되기까지 남은 유예 시간(분)을 화면 안내용으로 함께 준다. */
public record SignupResponse(Long id, String email, String nickname, long verificationTtlMinutes) {

  public static SignupResponse of(UserResponse user, long verificationTtlMinutes) {
    return new SignupResponse(user.id(), user.email(), user.nickname(), verificationTtlMinutes);
  }
}
