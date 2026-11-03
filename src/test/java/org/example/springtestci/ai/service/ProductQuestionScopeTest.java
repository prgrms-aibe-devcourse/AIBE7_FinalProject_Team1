package org.example.springtestci.ai.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProductQuestionScopeTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "저녁메뉴 추천 해줘",
        "오늘 저녁 뭐 먹지?",
        "점심으로 뭐 먹을까?",
        "메뉴 골라줘",
        "노트북 사양이랑 저녁 메뉴 추천해줘",
        "램 16GB인데 맛집 알려줘",
        "보증 만료일과 내일 날씨는?",
        "노트북으로 읽을 소설을 써줘",
        "제품 이력과 오늘의 운세 알려줘",
        "파이썬 코드를 작성해줘",
        "안녕",
        "What should I eat for dinner?",
        "Tell me a joke",
        "저 녁 메 뉴 추 천 해 줘",
        "메\u200b뉴 골라줘",
        "노트북 사양과 비트코인 시세 알려줘",
        "코인셀 배터리 이력과 코인 시세 알려줘",
        "코인셀 배터리 이력과 저녁 메뉴 추천해줘"
      })
  void unrelatedAndMixedRequestsAreOutside(String question) {
    assertTrue(ProductQuestionScope.isUnrelated(question), question);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "현재 메모리 사양은?",
        "보증 만료일은 언제인가요?",
        "배터리 교체 기록을 알려줘",
        "무효 처리된 수리 기록이 있나요?",
        "제품 정보는?",
        "언제 구매했고 누가 소유했나요?",
        "음료를 쏟아서 수리한 기록이 있나요?",
        "저녁에 수리한 기록도 있어?",
        "충전 상태 점검 이력은?",
        "What is the registered RAM?",
        "Show repair history",
        "ＣＰＵ 사양은?",
        "화면 크기는?",
        "코인셀 배터리 교체 기록이 있나요?",
        "코인 셀 배터리 교체 기록이 있나요?",
        "코인 배터리를 교체한 기록이 있나요?",
        "코인\u200b셀 교체 이력이 있나요?"
      })
  void registeredProductQuestionsStillReachTheirReadTools(String question) {
    assertFalse(ProductQuestionScope.isUnrelated(question), question);
  }
}
