package org.example.springtestci.admin.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.example.springtestci.admin.dto.AdminDtos.Facts;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 알려진 업무 필드만 투영한다. 임의 JSON·비공개 문서 경로·토큰을 그대로 반환하지 않는다. */
@Component
public class AdminAuditFacts {
  private static final Set<String> FIELDS =
      Set.of(
          "id",
          "productId",
          "authorId",
          "eventType",
          "occurredOn",
          "title",
          "content",
          "serviceProvider",
          "cost",
          "replacedPart",
          "status",
          "createdAt",
          "updatedAt",
          "changeType",
          "visibility",
          "requestStatus",
          "transactionStatus",
          "listingStatus",
          "confirmedFactsVersion",
          "cancelledAt",
          "completedAt",
          "ownerId",
          "fromOwnerId",
          "toOwnerId",
          "transferRequestId",
          "purchaseRequestId");
  private static final Set<String> SPECS =
      Set.of(
          "cpu",
          "gpu",
          "ramGb",
          "storageGb",
          "storageType",
          "display",
          "os",
          "batteryHealthPercent");
  private final ObjectMapper mapper;

  public AdminAuditFacts(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public Facts read(String raw) {
    if (raw == null || raw.isBlank()) return new Facts(Map.of(), true);
    if (raw.length() > 100_000) return new Facts(Map.of(), false);
    try {
      JsonNode node = mapper.readTree(raw);
      if (!node.isObject()) return legacy(raw);
      var fields = select(node, FIELDS);
      for (String key : Set.of("specsBefore", "specsAfter")) {
        JsonNode specifications = node.get(key);
        if (specifications != null && specifications.isTextual()) {
          try {
            specifications = mapper.readTree(specifications.asText());
          } catch (RuntimeException ignored) {
            specifications = null;
          }
        }
        if (specifications != null && specifications.isObject())
          fields.put(key, select(specifications, SPECS));
      }
      return new Facts(fields, !fields.isEmpty());
    } catch (RuntimeException exception) {
      return legacy(raw);
    }
  }

  private Map<String, Object> select(JsonNode node, Set<String> allowed) {
    var values = new LinkedHashMap<String, Object>();
    allowed.stream()
        .sorted()
        .forEach(
            key -> {
              JsonNode value = node.get(key);
              if (value == null || value.isNull()) values.put(key, null);
              else if (value.isValueNode() && !value.isBinary())
                values.put(key, mapper.convertValue(value, Object.class));
            });
    // 원본에 없는 필드는 표시하지 않아 '누락'과 실제 null을 구분한다.
    values.keySet().removeIf(key -> !node.has(key));
    return values;
  }

  private Facts legacy(String raw) {
    String value = raw.trim();
    if (value.startsWith("status=")) value = value.substring(7);
    if (Set.of("PENDING", "ACCEPTED", "REJECTED", "CANCELLED", "RESERVED", "COMPLETED")
        .contains(value)) return new Facts(Map.of("status", value), true);
    return new Facts(Map.of(), false);
  }
}
