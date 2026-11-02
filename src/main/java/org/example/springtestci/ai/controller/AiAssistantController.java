package org.example.springtestci.ai.controller;

import jakarta.validation.Valid;
import org.example.springtestci.ai.dto.AiAskRequest;
import org.example.springtestci.ai.dto.AiAskResponse;
import org.example.springtestci.ai.dto.AiPriceEstimateRequest;
import org.example.springtestci.ai.dto.AiPriceEstimateResponse;
import org.example.springtestci.ai.service.AiAssistantService;
import org.example.springtestci.common.response.ApiResponse;
import org.example.springtestci.common.security.CurrentUserIdResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/ai")
public class AiAssistantController {

  private final AiAssistantService aiService;
  private final CurrentUserIdResolver currentUserIdResolver;

  public AiAssistantController(
      AiAssistantService aiService, CurrentUserIdResolver currentUserIdResolver) {
    this.aiService = aiService;
    this.currentUserIdResolver = currentUserIdResolver;
  }

  @PostMapping("/ask")
  public ResponseEntity<ApiResponse<AiAskResponse>> ask(
      Authentication authentication, @Valid @RequestBody AiAskRequest request) {
    // 로그인한 사용자만 호출할 수 있다. 사용자 식별이 실패하면 익명으로 넘기지 않고 인증 오류로 끝낸다.
    long actorId = currentUserIdResolver.resolveAuthentication(authentication);
    AiAskResponse response = aiService.ask(actorId, request);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @PostMapping("/estimate-price")
  public ResponseEntity<ApiResponse<AiPriceEstimateResponse>> estimatePrice(
      @Valid @RequestBody AiPriceEstimateRequest request) {
    AiPriceEstimateResponse response = aiService.estimatePrice(request);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
