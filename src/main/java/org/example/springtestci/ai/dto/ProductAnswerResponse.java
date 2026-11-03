package org.example.springtestci.ai.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 근거는 모델이 만든 ID가 아니라 서버에서 실제 조회한 자료로 조립한다. 질문·대화는 저장하지 않는다. */
public record ProductAnswerResponse(
    String answer,
    List<Evidence> sources,
    long factsVersion,
    Long contentVersion,
    Instant generatedAt,
    boolean sample,
    Status status) {
  public ProductAnswerResponse {
    sources = List.copyOf(sources);
  }

  public enum Status {
    ANSWERED,
    NO_RECORD,
    OUT_OF_SCOPE
  }

  public record Evidence(String key, String type, String label, Map<String, Object> facts) {
    public Evidence {
      facts = Map.copyOf(facts);
    }
  }
}
