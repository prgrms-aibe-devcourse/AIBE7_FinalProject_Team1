package org.example.springtestci.ai.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.example.springtestci.common.error.CommonException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class GeminiListingSummaryGatewayTest {
  private final GeminiJsonClient client = mock(GeminiJsonClient.class);
  private final GeminiListingSummaryGateway gateway = new GeminiListingSummaryGateway(client);

  @Test
  void usesSharedTransportWithOneToTwoShortSentencesAndNoTools() {
    when(client.configured()).thenReturn(true);
    assertTrue(gateway.configured());
    when(client.generate(anyString(), anyMap(), eq("registered-source")))
        .thenReturn(
            JsonMapper.builder()
                .build()
                .readTree("{\"sentences\":[\"제품 특징을 소개합니다.\",\"등록된 상태를 설명합니다.\"]}"));
    assertEquals(2, gateway.summarize("registered-source").size());
    var schema = ArgumentCaptor.forClass(Map.class);
    var instructions = ArgumentCaptor.forClass(String.class);
    verify(client).generate(instructions.capture(), schema.capture(), eq("registered-source"));
    assertTrue(instructions.getValue().contains("메모리 → 저장장치"));
    assertTrue(instructions.getValue().contains("유효한 사양 변경·부품 교체 → 수리 → 점검 → 구매·양도"));
    assertTrue(instructions.getValue().contains("90자 한도와 사실·안전 규칙을 우선"));
    Map<?, ?> properties = (Map<?, ?>) schema.getValue().get("properties");
    Map<?, ?> sentences = (Map<?, ?>) properties.get("sentences");
    assertEquals(1, sentences.get("minItems"));
    assertEquals(2, sentences.get("maxItems"));
    assertEquals(90, ((Map<?, ?>) sentences.get("items")).get("maxLength"));
    assertEquals(false, schema.getValue().get("additionalProperties"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"sentences\":\"text\"}",
        "{\"sentences\":[1,2,3]}",
        "{\"sentences\":[],\"extra\":true}"
      })
  void rejectsWrongShapeBeforeBusinessValidation(String output) {
    when(client.generate(anyString(), anyMap(), anyString()))
        .thenReturn(JsonMapper.builder().build().readTree(output));
    assertThrows(CommonException.class, () -> gateway.summarize("{}"));
  }
}
