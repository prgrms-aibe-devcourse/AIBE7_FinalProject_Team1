package org.example.springtestci.admin.controller;

import org.example.springtestci.admin.dto.AdminDtos.*;
import org.example.springtestci.admin.service.AdminProgressService;
import org.example.springtestci.admin.service.AdminReadService;
import org.example.springtestci.admin.service.AdminTimelineService;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.common.security.CurrentUserIdResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** ADM-01~04 조회 전용 API. 관리자 권한은 SecurityConfig와 서비스 양쪽에서 검사한다. */
@RestController
public class AdminController {
  private final AdminReadService service;
  private final AdminTimelineService timelines;
  private final AdminProgressService progress;
  private final CurrentUserIdResolver userIds;

  public AdminController(
      AdminReadService service,
      AdminTimelineService timelines,
      AdminProgressService progress,
      CurrentUserIdResolver userIds) {
    this.service = service;
    this.timelines = timelines;
    this.progress = progress;
    this.userIds = userIds;
  }

  @GetMapping(
      value = {"/admin", "/admin/"},
      produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<Resource> page() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new ClassPathResource("static/admin.html"));
  }

  /** 상태·취소 유형으로 좁힌 목록과, 그 선택 전의 제품·회원·예약일 범위 요약을 함께 제공한다. */
  @GetMapping("/api/v1/admin/trades")
  public ApiResponse<TradePage> trades(
      @AuthenticationPrincipal Jwt jwt, @ModelAttribute Filter filter) {
    return ApiResponse.success(service.trades(userIds.resolve(jwt), filter));
  }

  @GetMapping("/api/v1/admin/trades/{id}")
  public ApiResponse<Trade> trade(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
    return ApiResponse.success(service.trade(userIds.resolve(jwt), id));
  }

  /** 구매 요청 ID에 실제로 연결된 사건의 시간순 페이지를 관리자에게만 제공한다. */
  @GetMapping("/api/v1/admin/trades/{id}/timeline")
  public ApiResponse<TradeTimeline> timeline(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable long id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success(timelines.timeline(userIds.resolve(jwt), id, page, size));
  }

  /** 구매 요청 ID의 현재 확인 대기 단계를 읽는다. 거래를 완료하거나 확인 버전을 저장하지 않는다. */
  @GetMapping("/api/v1/admin/trades/{id}/progress")
  public ApiResponse<TradeProgress> progress(
      @AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
    return ApiResponse.success(progress.progress(userIds.resolve(jwt), id));
  }

  @GetMapping("/api/v1/admin/audit-logs")
  public ApiResponse<Page<Audit>> audits(
      @AuthenticationPrincipal Jwt jwt, @ModelAttribute Filter filter) {
    return ApiResponse.success(service.audits(userIds.resolve(jwt), filter));
  }

  @GetMapping("/api/v1/admin/audit-logs/{id}")
  public ApiResponse<AuditDetail> audit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
    return ApiResponse.success(service.audit(userIds.resolve(jwt), id));
  }
}
