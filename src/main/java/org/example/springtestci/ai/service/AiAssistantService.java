package org.example.springtestci.ai.service;

import java.util.ArrayList;
import java.util.List;
import org.example.springtestci.ai.dto.AiAskRequest;
import org.example.springtestci.ai.dto.AiAskResponse;
import org.example.springtestci.ai.dto.AiPriceEstimateRequest;
import org.example.springtestci.ai.dto.AiPriceEstimateResponse;
import org.example.springtestci.product.repository.ProductRepository;
import org.springframework.stereotype.Service;

@Service
public class AiAssistantService {

  private final AiSecurityValidator securityValidator;
  private final ProductRepository productRepository;

  public AiAssistantService(
      AiSecurityValidator securityValidator, ProductRepository productRepository) {
    this.securityValidator = securityValidator;
    this.productRepository = productRepository;
  }

  public AiAskResponse ask(Long actorId, AiAskRequest request) {
    // SEC-06: 지시문 탈취 및 프롬프트 인젝션 검증
    securityValidator.validateUserInput(request.query());

    List<String> usedTools = new ArrayList<>();
    String productContext = "";

    if (request.productId() != null) {
      // SEC-06: 허용된 조회 도구만 호출
      securityValidator.validateToolExecution(AiToolType.GET_PRODUCT_SPEC);
      usedTools.add(AiToolType.GET_PRODUCT_SPEC.name());

      // SEC-02·04: 비로그인도 호출할 수 있으므로 제품 소유자 본인에게만 제품 정보를 사용한다.
      productContext =
          productRepository
              .findOwnerId(request.productId())
              .filter(ownerId -> ownerId.equals(actorId))
              .flatMap(ownerId -> productRepository.findTitle(request.productId()))
              .map(t -> "[" + t + "] 에 대해 ")
              .orElse("");
    }

    // 기본 응답 생성 및 XSS 방어 새니타이징 (SEC-05)
    String rawAnswer =
        productContext
            + "문의하신 내용("
            + request.query()
            + ")에 대한 분석 결과입니다. 중고 노트북 거래 시 배터리 상태와 보증 기간 및 사설 수리 이력을 반드시 점검하시는 것을 권장합니다.";
    String sanitizedAnswer = securityValidator.sanitizeText(rawAnswer);

    return new AiAskResponse(request.query(), sanitizedAnswer, false, usedTools);
  }

  public AiPriceEstimateResponse estimatePrice(AiPriceEstimateRequest req) {
    securityValidator.validateUserInput(req.modelName());

    long basePrice = 1_000_000L;
    if (req.modelName().toLowerCase().contains("pro")
        || req.modelName().toLowerCase().contains("m3")) {
      basePrice = 1_800_000L;
    } else if (req.modelName().toLowerCase().contains("air")) {
      basePrice = 1_200_000L;
    }

    if (req.ramGb() != null && req.ramGb() >= 32) basePrice += 300_000L;
    else if (req.ramGb() != null && req.ramGb() >= 16) basePrice += 150_000L;

    if (req.storageGb() != null && req.storageGb() >= 1024) basePrice += 200_000L;

    if (req.batteryHealthPercent() != null && req.batteryHealthPercent() < 80) {
      basePrice -= 150_000L;
    }

    long min = (long) (basePrice * 0.9);
    long max = (long) (basePrice * 1.1);
    String reasoning =
        String.format(
            "%s 모델의 사양(RAM %dGB, 저장공간 %dGB, 배터리 효율 %d%%)을 기반으로 산출된 추천 시세입니다.",
            req.modelName(),
            req.ramGb() != null ? req.ramGb() : 8,
            req.storageGb() != null ? req.storageGb() : 256,
            req.batteryHealthPercent() != null ? req.batteryHealthPercent() : 100);

    return new AiPriceEstimateResponse(
        min, max, basePrice, securityValidator.sanitizeText(reasoning));
  }
}
