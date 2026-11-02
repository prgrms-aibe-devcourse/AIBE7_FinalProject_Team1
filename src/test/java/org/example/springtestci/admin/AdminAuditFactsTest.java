package org.example.springtestci.admin;

import static org.junit.jupiter.api.Assertions.*;

import org.example.springtestci.admin.service.AdminAuditFacts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AdminAuditFactsTest {
  private final AdminAuditFacts facts = new AdminAuditFacts(JsonMapper.builder().build());

  @Test
  void unknownOrMalformedLegacyRecordsDoNotReturnRawSecrets() {
    for (String value :
        new String[] {
          "token=private", "{bad-json:private}", "[\"private\"]", "{\"token\":\"private\"}"
        }) {
      var result = facts.read(value);
      assertFalse(result.available());
      assertTrue(result.values().isEmpty());
    }
  }

  @Test
  void specificationAllowlistAppliesToObjectsAndEncodedJson() {
    var result =
        facts.read(
            "{\"specsBefore\":{\"ramGb\":16,\"objectKey\":\"private\"},\"specsAfter\":\"{\\\"ramGb\\\":32,\\\"serialNumber\\\":\\\"private\\\"}\"}");
    assertTrue(result.available());
    assertEquals(
        "{specsAfter={ramGb=32}, specsBefore={ramGb=16}}",
        new java.util.TreeMap<>(result.values()).toString());
    assertFalse(result.toString().contains("private"));
  }

  @Test
  void distinguishesNullFromAbsentFieldsAndKeepsBusinessReasonableValues() {
    var result = facts.read("{\"content\":null,\"cost\":0,\"title\":\"메모리 점검\"}");
    assertTrue(result.values().containsKey("content"));
    assertNull(result.values().get("content"));
    assertFalse(result.values().containsKey("eventType"));
    assertEquals(0, result.values().get("cost"));
  }

  @Test
  void largeSnapshotsAreNotParsedAndNullFactsStayEmpty() {
    assertFalse(facts.read("x".repeat(100_001)).available());
    assertTrue(facts.read(null).available());
    assertTrue(facts.read(null).values().isEmpty());
  }
}
