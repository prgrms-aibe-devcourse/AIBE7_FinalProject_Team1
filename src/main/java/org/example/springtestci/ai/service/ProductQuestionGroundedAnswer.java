package org.example.springtestci.ai.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import org.example.springtestci.ai.dto.ProductAnswerResponse.Evidence;
import org.example.springtestci.listing.service.PublicHistoryTextPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 무효 이력을 읽은 답변은 모델 문장을 사용하지 않고 서버가 조회한 구조화된 사실만 설명한다. */
final class ProductQuestionGroundedAnswer {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String NOTICE =
      "조회한 이력에 무효 처리된 기록이 포함되어 있어요. 무효 기록은 실제 발생 사실의 근거로 사용하지 않아요.";
  private static final int MAX_LENGTH = 2000;
  private static final String PARTIAL_NOTICE =
      "답변에는 일부 근거 또는 상세만 담았어요. 답변 아래 근거와 전체 이력을 함께 확인해 주세요.";

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
    var text = new AnswerText(selected.size() < delivered.size());
    text.add(NOTICE);
    if (missingWarranty) text.add("보증 만료일이 등록되지 않아 현재 보증 여부를 확인할 수 없어요.");
    // 이력의 존재·무효 여부를 먼저 남긴다. 긴 상세 하나 때문에 다른 사건의 상태까지 사라지지 않게 한다.
    for (var item : selected.values()) {
      if (!"EVENT".equals(item.type())) continue;
      var facts = JSON.valueToTree(item.facts());
      String record = recordLabel(facts);
      if (voided(item)) text.add(record + "은 무효 처리되어 실제 발생 여부를 확인할 수 없어요.");
      else {
        String corrected = facts.path("corrected").asBoolean() ? "정정된 " : "";
        String detail = facts.path("detailsReviewed").asBoolean() ? "" : " 공개된 세부 내용은 확인할 수 없어요.";
        text.add(corrected + record + "이 등록되어 있어요." + detail);
      }
    }
    for (var item : selected.values()) {
      var facts = JSON.valueToTree(item.facts());
      if ("SPECS".equals(item.type())) addSpecs(text, facts);
      else if ("WARRANTY".equals(item.type()))
        text.add("등록된 보증 만료일은 " + facts.path("warrantyExpiresOn").asString() + "이에요.");
    }
    // 짧은 공개 제목·부품을 본문보다 먼저 담는다. 한도를 넘는 필드는 통째로 생략해 문장을 자르지 않는다.
    for (var item : selected.values()) {
      if (!"EVENT".equals(item.type())) continue;
      var facts = JSON.valueToTree(item.facts());
      addPublicDetail(text, facts, "title", "공개 제목", 200);
      addPublicDetail(text, facts, "replacedPart", "교체 부품", 200);
    }
    for (var item : selected.values()) {
      if ("EVENT".equals(item.type()))
        addPublicDetail(text, JSON.valueToTree(item.facts()), "content", "공개 내용", 2000);
    }
    return new Result(text.finish(), List.copyOf(selected.values()));
  }

  private static boolean voided(Evidence item) {
    return "EVENT".equals(item.type()) && "VOID".equals(item.facts().get("status"));
  }

  private static String recordLabel(JsonNode facts) {
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
    return facts.path("occurredOn").asString() + " " + label + " 기록";
  }

  /** 무효 본문은 사용하지 않으며 공개 필드 검사도 다시 적용한다. 원래 근거 데이터는 자르지 않는다. */
  private static void addPublicDetail(
      AnswerText text, JsonNode facts, String field, String label, int max) {
    if (!"VALID".equals(facts.path("status").asString())
        || !facts.path("detailsReviewed").asBoolean()) return;
    var value = facts.path(field);
    if (!value.isString()) return;
    String allowed = PublicHistoryTextPolicy.clean(value.asString(), max);
    if (allowed != null) text.add(recordLabel(facts) + " · " + label + ": " + allowed);
  }

  private static void addSpecs(AnswerText text, JsonNode facts) {
    boolean visible = add(text, facts, "manufacturer", "제조사 ", "");
    visible |= add(text, facts, "modelName", "모델 ", "");
    var specs = facts.path("specifications");
    visible |= add(text, specs, "cpu", "CPU ", "");
    visible |= add(text, specs, "gpu", "GPU ", "");
    visible |= add(text, specs, "ramGb", "메모리 ", "GB");
    visible |= add(text, specs, "storageGb", "저장 용량 ", "GB");
    visible |= add(text, specs, "storageType", "저장 방식 ", "");
    visible |= add(text, specs, "display", "화면 ", "");
    visible |= add(text, specs, "os", "운영체제 ", "");
    visible |= add(text, specs, "batteryHealthPercent", "배터리 상태 ", "%");
    if (!visible) text.add("등록된 세부 사양은 답변 아래 근거에서 확인해 주세요.");
  }

  private static boolean add(
      AnswerText text, JsonNode data, String field, String label, String unit) {
    var value = data.path(field);
    if ((value.isString() || value.isNumber()) && !value.asString().isBlank())
      return text.add("등록된 사양 · " + label + value.asString() + unit + ".");
    return false;
  }

  /** 생략 안내를 위한 공간을 미리 확보하고, 들어갈 수 있는 완전한 항목만 누적한다. */
  private static final class AnswerText {
    private final List<String> lines = new ArrayList<>();
    private int length;
    private boolean omitted;

    private AnswerText(boolean omitted) {
      this.omitted = omitted;
    }

    private boolean add(String line) {
      int added = line.length() + (lines.isEmpty() ? 0 : 1);
      if (length + added > MAX_LENGTH - PARTIAL_NOTICE.length() - 1) {
        omitted = true;
        return false;
      }
      lines.add(line);
      length += added;
      return true;
    }

    private String finish() {
      return String.join("\n", lines) + (omitted ? "\n" + PARTIAL_NOTICE : "");
    }
  }

  record Result(String text, List<Evidence> evidence) {}
}
