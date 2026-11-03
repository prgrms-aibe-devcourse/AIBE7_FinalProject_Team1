package org.example.springtestci.ai.service;

import java.util.List;
import java.util.Map;

/** 모델은 허용 조회 도구 또는 범위 밖 종료를 선택한다. 실제 대상과 권한은 서버의 요청 범위에 고정한다. */
public interface ProductQuestionGateway {
  boolean configured();

  Answer ask(String question, Tools tools);

  interface Tools {
    Map<String, Object> read(String name);
  }

  enum Scope {
    PRODUCT_RECORDS,
    OUT_OF_SCOPE
  }

  record Answer(String answer, List<String> sourceKeys, Scope scope) {
    public Answer(String answer, List<String> sourceKeys) {
      this(answer, sourceKeys, Scope.PRODUCT_RECORDS);
    }

    public static Answer outsideScope() {
      return new Answer("", List.of(), Scope.OUT_OF_SCOPE);
    }
  }
}
