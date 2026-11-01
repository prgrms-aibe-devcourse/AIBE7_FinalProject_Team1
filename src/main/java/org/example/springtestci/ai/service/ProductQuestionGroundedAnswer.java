package org.example.springtestci.ai.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import org.example.springtestci.ai.dto.ProductAnswerResponse.Evidence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 무효 이력을 읽은 답변은 모델 문장을 사용하지 않고 서버가 조회한 구조화된 사실만 설명한다. */
final class ProductQuestionGroundedAnswer {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String NOTICE =
      "조회한 이력에 무효 처리된 기록이 포함되어 있어요. 무효 기록은 실제 발생 사실의 근거로 사용하지 않아요.";

  private ProductQuestionGroundedAnswer() {}

  /** 모델이 무효 근거를 인용에서 빼더라도 안내가 빠지지 않게 실제 전달한 무효 근거를 우선 포함한다. */
  static Result compose(
      List<Evidence> cited, Collection<Evidence> delivered, boolean missingWarranty) {
    var voided = delivered.stream().filter(ProductQuestionGroundedAnswer::voided).findFirst();
    if (voided.isEmpty()) return null;
    var selected = new LinkedHashMap<String, Evidence>();
    selected.put(voided.get().key(), voided.get());
    // 이미 조회한 사양·보증은 모델이 인용을 빠뜨려도 유지한다. 이력 근거를 포함해 전체 최대 20개다.
    for (var item : delivered) {
      if ("SPECS".equals(item.type()) || "WARRANTY".equals(item.type()))
        selected.put(item.key(), item);
    }
    for (var item : cited) {
      if (selected.size() < 20) selected.putIfAbsent(item.key(), item);
    }
    for (var item : delivered) {
      if (selected.size() < 20) selected.putIfAbsent(item.key(), item);
    }
    List<String> lines = new ArrayList<>();
    lines.add(NOTICE);
    String warrantyNotice = missingWarranty ? "보증 만료일이 등록되지 않아 현재 보증 여부를 확인할 수 없어요." : "";
    if (!warrantyNotice.isEmpty()) lines.add(warrantyNotice);
    for (var item : selected.values()) {
      var facts = JSON.valueToTree(item.facts());
      switch (item.type()) {
        case "SPECS" -> lines.add(specs(facts));
        case "WARRANTY" ->
            lines.add("등록된 보증 만료일은 " + facts.path("warrantyExpiresOn").asString() + "이에요.");
        case "EVENT" -> {
          String label =
              switch (facts.path("type").asString()) {
                case "PURCHASE" -> "구매";
                case "INSPECTION" -> "점검";
                case "REPAIR" -> "수리";
                case "PART_REPLACEMENT" -> "부품 교체";
                case "SPEC_CHANGE" -> "사양 변경";
                case "TRANSFER" -> "소유권 이전";
                default -> "제품";
              };
          String record = facts.path("occurredOn").asString() + " " + label + " 기록";
          if (voided(item)) lines.add(record + "은 무효 처리되어 실제 발생 여부를 확인할 수 없어요.");
          else {
            String corrected = facts.path("corrected").asBoolean() ? "정정된 " : "";
            String pending = facts.path("detailsReviewed").asBoolean() ? "" : " 세부 내용은 미검토예요.";
            lines.add(corrected + record + "이 등록되어 있어요." + pending);
          }
        }
        default -> {}
      }
    }
    String partial =
        selected.size() < delivered.size() ? "\n답변에는 일부 근거만 담았어요. 전체 이력도 함께 확인해 주세요." : "";
    String text = String.join("\n", lines) + partial;
    // 긴 근거를 잘라 다른 의미로 만들지 않고, 안내와 실제 근거 목록을 제공한다. 자유 입력 본문은 재사용하지 않는다.
    if (text.length() > 2000)
      text =
          NOTICE
              + " "
              + warrantyNotice
              + " 유효한 기록도 세부 내용이 미검토라면 유형·날짜·상태만 확인할 수 있어요. 제품 사양과 이력 근거를 함께 확인해 주세요."
              + partial;
    return new Result(text, List.copyOf(selected.values()));
  }

  private static boolean voided(Evidence item) {
    return "EVENT".equals(item.type()) && "VOID".equals(item.facts().get("status"));
  }

  private static String specs(JsonNode facts) {
    List<String> values = new ArrayList<>();
    add(values, facts, "manufacturer", "제조사 ", "");
    add(values, facts, "modelName", "모델 ", "");
    var specs = facts.path("specifications");
    add(values, specs, "cpu", "CPU ", "");
    add(values, specs, "gpu", "GPU ", "");
    add(values, specs, "ramGb", "메모리 ", "GB");
    add(values, specs, "storageGb", "저장 용량 ", "GB");
    add(values, specs, "storageType", "저장 방식 ", "");
    add(values, specs, "display", "화면 ", "");
    add(values, specs, "os", "운영체제 ", "");
    add(values, specs, "batteryHealthPercent", "배터리 상태 ", "%");
    return values.isEmpty()
        ? "등록된 세부 사양을 확인할 수 없어요."
        : "등록된 사양: " + String.join(", ", values) + ".";
  }

  private static void add(
      List<String> values, JsonNode data, String field, String label, String unit) {
    var value = data.path(field);
    if ((value.isString() || value.isNumber()) && !value.asString().isBlank())
      values.add(label + value.asString() + unit);
  }

  record Result(String text, List<Evidence> evidence) {}
}
