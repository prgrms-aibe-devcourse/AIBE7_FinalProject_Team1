package org.example.springtestci.admin.service;

import org.example.springtestci.admin.repository.AdminAccessRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAccessService {
  private final AdminAccessRepository repository;

  public AdminAccessService(AdminAccessRepository repository) {
    this.repository = repository;
  }

  /** 화면과 API 모두 요청할 때마다 현재 역할을 검사해 권한 회수가 즉시 반영되게 한다. */
  @Transactional(readOnly = true)
  public boolean allowed(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof Jwt jwt)) return false;
    try {
      long id = Long.parseLong(jwt.getSubject());
      return id > 0 && repository.isAdmin(id);
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  @Transactional(readOnly = true)
  public void requireAdmin(long userId) {
    if (!repository.isAdmin(userId)) throw new CommonException(ErrorCode.ACCESS_DENIED);
  }
}
