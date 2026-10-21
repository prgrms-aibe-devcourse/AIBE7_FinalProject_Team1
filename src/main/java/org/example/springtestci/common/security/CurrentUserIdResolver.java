package org.example.springtestci.common.security;

import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserIdResolver {

  /** JWT subject를 검증하고 양수 사용자 ID로 변환한다. */
  public long resolve(Jwt jwt) {
    if (jwt == null || jwt.getSubject() == null) {
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    }

    try {
      long userId = Long.parseLong(jwt.getSubject());
      if (userId <= 0) {
        throw new NumberFormatException("user id must be positive");
      }
      return userId;
    } catch (NumberFormatException exception) {
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    }
  }
}
