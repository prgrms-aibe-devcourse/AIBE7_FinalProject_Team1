package org.example.springtestci.ai.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** 명백한 잡담·무관한 복합 질문은 외부 AI 호출 전에 제외한다. 애매한 의미 판단은 모델의 범위 밖 경로로 보완한다. */
final class ProductQuestionScope {
  private static final Pattern PRODUCT_TOPIC =
      Pattern.compile(
          "(?iu)(제품|물건|노트북|랩톱|사양|스펙|제조사|모델|구매|구입|샀|보증|이력|기록|수리|수선|점검|검사|교체|부품|정정|무효|소유|양도|이전|배터리|밧데리|충전|메모리|램|저장|용량|디스플레이|화면|운영체제|윈도우|리퍼|사용|썼|변경|상태|무슨\\s*일|뭔\\s*일|\\b(product|laptop|notebook|specs?|specifications?|manufacturer|model|purchase|warranty|history|records?|repair|inspection|replac\\w*|battery|memory|ram|cpu|gpu|ssd|hdd|storage|display|os)\\b)");
  private static final Pattern UNRELATED =
      Pattern.compile(
          "(?iu)(추천|메뉴|맛집|레시피|요리법|뭐먹|뭘먹|무엇을먹|(?:아침|점심|저녁)(?:식사|뭐|으로|추천)|음식추천|식사추천|날씨|미세먼지|운세|로또|주식|주가|코인|연애|농담|유머|소설|시(?:를)?써|코드작성|코딩해|dinner|lunch|breakfast|recipe|restaurant|weather|horoscope|tellmeajoke|write(?:me)?(?:apoem|astory|code))");

  private ProductQuestionScope() {}

  static boolean isUnrelated(String question) {
    String normalized =
        Normalizer.normalize(question, Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "");
    String compact = normalized.replaceAll("\\s+", "");
    // 제품 단어를 앞에 붙여도 메뉴·날씨 등 다른 요청을 섞으면 질문 전체를 범위 밖으로 처리한다.
    return UNRELATED.matcher(compact).find() || !PRODUCT_TOPIC.matcher(normalized).find();
  }
}
