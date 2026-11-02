package org.example.springtestci.history.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.common.security.CurrentUserIdResolver;
import org.example.springtestci.history.dto.CorrectHistoryEventRequest;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.dto.HistoryEventResponse;
import org.example.springtestci.history.dto.VoidHistoryEventRequest;
import org.example.springtestci.history.service.HistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 제품 이력 API의 HTTP 요청을 받는 진입점이다.
 *
 * <p>JWT에서 현재 사용자 ID를 구한 뒤 등록·조회·정정·무효화 업무를 {@link HistoryService}에 위임한다.
 */
@Validated
@RestController
@RequestMapping("/api/v1/products/{productId}/events")
public class HistoryController {

  // 이력 관련 업무 규칙과 트랜잭션은 Service에서 처리한다.
  private final HistoryService historyService;

  // JWT 해석 방식을 Controller마다 중복 구현하지 않도록 공통 Resolver를 사용한다.
  private final CurrentUserIdResolver currentUserIdResolver;

  public HistoryController(
      HistoryService historyService, CurrentUserIdResolver currentUserIdResolver) {
    this.historyService = historyService;
    this.currentUserIdResolver = currentUserIdResolver;
  }

  /** 소유 제품에 새로운 이력 이벤트를 등록하고 HTTP 201 Created를 반환한다. */
  @PostMapping
  public ResponseEntity<ApiResponse<HistoryEventResponse>> create(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @Valid @RequestBody CreateHistoryEventRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            ApiResponse.success(
                historyService.create(currentUserIdResolver.resolve(jwt), productId, request)));
  }

  /** 제품 이력을 시간순 목록으로 조회한다. */
  @GetMapping
  public ApiResponse<List<HistoryEventResponse>> timeline(
      @AuthenticationPrincipal Jwt jwt, @PathVariable @Positive long productId) {
    return ApiResponse.success(
        historyService.timeline(currentUserIdResolver.resolve(jwt), productId));
  }

  /** 작성자가 만든 유효한 이력 이벤트의 내용을 정정한다. */
  @PatchMapping("/{eventId}")
  public ApiResponse<HistoryEventResponse> correct(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @PathVariable @Positive long eventId,
      @Valid @RequestBody CorrectHistoryEventRequest request) {
    return ApiResponse.success(
        historyService.correct(currentUserIdResolver.resolve(jwt), productId, eventId, request));
  }

  /** 이력을 삭제하지 않고 무효 상태로 변경해 감사 기록을 보존한다. */
  @PostMapping("/{eventId}/void")
  public ApiResponse<HistoryEventResponse> voidEvent(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable @Positive long productId,
      @PathVariable @Positive long eventId,
      @Valid @RequestBody VoidHistoryEventRequest request) {
    return ApiResponse.success(
        historyService.voidEvent(currentUserIdResolver.resolve(jwt), productId, eventId, request));
  }
}
