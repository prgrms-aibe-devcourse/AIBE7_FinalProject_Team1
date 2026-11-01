package org.example.springtestci.listing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.example.springtestci.listing.service.ListingHistoryReader.Correction;
import org.example.springtestci.listing.service.ListingHistoryReader.PublicText;
import org.example.springtestci.listing.service.PublicHistoryTextPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicHistoryTextPolicyTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-10-01 배터리 교체 완료",
        "SSD 512GB 교체 후 정상 동작 확인",
        "RAM 16GB → 32GB",
        "공식 서비스센터",
        "발생일 오기입 정정",
        "중복 등록으로 무효 처리",
        "보증기간 내 무상 수리"
      })
  void productFactsAndCorrectionReasonsAreAvailableWithoutAnAdminApproval(String value) {
    assertEquals(value, PublicHistoryTextPolicy.clean(value, 2000));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "담당자 홍길동에게 배터리 교체 의뢰",
        "고객명: 홍길동",
        "홍길동님 요청으로 점검",
        "서울시 강남구 테헤란로 123에서 수령",
        "배송 주소: 서울",
        "010-1234-5678",
        "+82 10 1234 5678",
        "user@fixture.invalid",
        "ｕｓｅｒ＠ｆｉｘｔｕｒｅ．ｉｎｖａｌｉｄ",
        "900101-1234567",
        "카드 1234 5678 9012 3456",
        "계좌 123-456-789",
        "주문 번호 ABC-123",
        "수리비 50,000원",
        "구매가 20만원",
        "$500 paid for repair",
        "시리얼 ABCDEF123456",
        "S/N: ABCDEF123456",
        "영수증 첨부",
        "invoice: XYZ",
        "https://private.invalid/file.pdf",
        "<script>alert(1)</script>",
        "연락\u200b처 01012345678",
        "배터리\u202e개인정보",
        "비공개\u0001내용"
      })
  void excludesTheWholeSensitiveFieldRatherThanPretendingPartialRedactionIsComplete(String value) {
    assertNull(PublicHistoryTextPolicy.clean(value, 2000));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "010\u20111234\u20115678", "010\u20131234\u20135678", "010\u22121234\u22125678",
        "010-\n1234-5678", "010 -\n 1234-\t5678", "+82\u201110\u20111234\u20115678",
        "900101\u20111234567", "카드 1234\u20115678\u20119012\u20113456"
      })
  void unicodeAndMultilineContactsAreExcludedAtThePublicDtoBoundary(String value) {
    var text = new PublicText("배터리 점검", value, "공식 서비스센터", "배터리", true);
    assertNull(text.content());
    assertNull(new Correction(1, Instant.EPOCH, value, true).reason());
    assertEquals("배터리 점검", text.title());
    assertEquals("공식 서비스센터", text.serviceProvider());
    assertEquals("배터리", text.replacedPart());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "수리비 50000",
        "구매금액 50000",
        "구매 금액 50,000",
        "비용 50000",
        "금액 50000",
        "repair cost 50000",
        "purchase price 50000",
        "수리비 5만 원"
      })
  void costLabelsAreExcludedEvenWhenTheCurrencyUnitIsMissing(String value) {
    assertNull(PublicHistoryTextPolicy.clean(value, 2000));
  }

  @Test
  void oneSensitiveFieldDoesNotHideOtherProductFactsOrChangeTheOriginal() {
    String original = "배터리 교체, 담당자 홍길동 010-1234-5678";
    var text = new PublicText("배터리 교체", original, "공식 서비스센터", "배터리", true);
    assertEquals("배터리 교체", text.title());
    assertNull(text.content());
    assertEquals("공식 서비스센터", text.serviceProvider());
    assertEquals("배터리", text.replacedPart());
    assertEquals("배터리 교체, 담당자 홍길동 010-1234-5678", original);
  }

  @Test
  void blankAndOversizedFieldsAreNotPublishedOrSilentlyTruncated() {
    assertNull(PublicHistoryTextPolicy.clean(null, 200));
    assertNull(PublicHistoryTextPolicy.clean(" \n\t", 200));
    assertNull(PublicHistoryTextPolicy.clean("가".repeat(201), 200));
    assertEquals("배터리 교체", PublicHistoryTextPolicy.clean("  배터리 교체  ", 200));
  }
}
