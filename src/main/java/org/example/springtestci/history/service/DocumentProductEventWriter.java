package org.example.springtestci.history.service;

import java.util.ArrayList;
import java.util.List;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.document.dto.ExtractedFields;
import org.example.springtestci.document.service.ProductEventWriter;
import org.example.springtestci.history.dto.CreateHistoryEventRequest;
import org.example.springtestci.history.entity.HistoryEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자가 확정한 문서 추출값을 제품 이력으로 저장하는 운영 구현체.
 *
 * <p>입력은 제품 ID·문서 ID·현재 소유자 ID와 사용자가 검토한 값이다. 이력 저장·증빙 연결·보호 표시·제품 버전 증가는 기존 {@link
 * HistoryService#create}를 그대로 사용하므로 직접 등록한 이력과 같은 검증(소유권, 미래 날짜, 외부 이전 진행 중 차단, 증빙의 마스킹 확인)을 받는다.
 *
 * <p>구매일이 있으면 구매 이력, 수리일이 있으면 수리 이력을 만든다. 둘 다 있으면 두 건을 만들고 같은 문서를 두 이력에 연결한다. 날짜를 임의로 채우지 않으므로 둘 다
 * 없으면 이력을 만들지 않고 거부한다.
 */
@Service
public class DocumentProductEventWriter implements ProductEventWriter {

  private static final int CONTENT_MAX = 2000;

  private final HistoryService historyService;

  public DocumentProductEventWriter(HistoryService historyService) {
    this.historyService = historyService;
  }

  /**
   * 확정 서비스가 시작한 트랜잭션에 반드시 참여한다. 이력 저장과 추출 확정 상태가 함께 커밋되거나 함께 롤백된다.
   *
   * @return 처음 만든 이력의 ID. 구매·수리 두 건이면 구매 이력의 ID
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Long recordFromDocument(
      Long productId, Long documentId, Long userId, ExtractedFields fields) {
    List<CreateHistoryEventRequest> requests = plan(fields, documentId);
    Long first = null;
    for (CreateHistoryEventRequest request : requests) {
      long eventId = historyService.create(userId, productId, request).eventId();
      if (first == null) first = eventId;
    }
    return first;
  }

  /** 확정값에서 만들 이력 요청을 정한다. 화면의 미리보기(doc-extraction.js)도 같은 규칙을 따른다. */
  static List<CreateHistoryEventRequest> plan(ExtractedFields fields, Long documentId) {
    boolean purchase = fields.purchaseDate() != null;
    boolean repair = fields.repairDate() != null;
    if (!purchase && !repair) {
      throw new CommonException(ErrorCode.DOCUMENT_EVENT_DATE_REQUIRED);
    }
    List<Long> documents = List.of(documentId);
    List<CreateHistoryEventRequest> requests = new ArrayList<>();
    if (purchase) {
      requests.add(
          new CreateHistoryEventRequest(
              HistoryEventType.PURCHASE,
              fields.purchaseDate(),
              fields.productName() == null ? "구매" : fields.productName() + " 구매",
              requireWithinLimit(content(fields, !repair)),
              fields.vendor(),
              fields.price(),
              null,
              null,
              documents));
    }
    if (repair) {
      // 구매일도 있으면 구매 이력에 제품·구매처·금액을 담고, 수리 이력에는 수리 내용만 남긴다.
      boolean carryProductInfo = !purchase;
      requests.add(
          new CreateHistoryEventRequest(
              HistoryEventType.REPAIR,
              fields.repairDate(),
              "수리",
              requireWithinLimit(repairContent(fields, carryProductInfo)),
              carryProductInfo ? fields.vendor() : null,
              carryProductInfo ? fields.price() : null,
              null,
              null,
              documents));
    }
    return requests;
  }

  /** 이력 테이블에 칸이 없는 제품명·모델명·보증기간은 content에 글로 남긴다. */
  private static String content(ExtractedFields fields, boolean includeRepair) {
    List<String> lines = productLines(fields);
    if (includeRepair && fields.repairDescription() != null) {
      lines.add("수리 내용: " + fields.repairDescription());
    }
    return lines.isEmpty() ? null : String.join("\n", lines);
  }

  private static String repairContent(ExtractedFields fields, boolean carryProductInfo) {
    List<String> lines = carryProductInfo ? productLines(fields) : new ArrayList<>();
    if (fields.repairDescription() != null) lines.add("수리 내용: " + fields.repairDescription());
    return lines.isEmpty() ? null : String.join("\n", lines);
  }

  private static List<String> productLines(ExtractedFields fields) {
    List<String> lines = new ArrayList<>();
    if (fields.productName() != null) lines.add("제품명: " + fields.productName());
    if (fields.modelName() != null) lines.add("모델명: " + fields.modelName());
    if (fields.warrantyMonths() != null) lines.add("보증기간: " + fields.warrantyMonths() + "개월");
    return lines;
  }

  /** 이력 내용의 길이 제한을 넘으면 거부한다. 잘라서 저장하면 미리보기에서 본 내용과 저장된 내용이 달라지고, 확정한 수리 내용의 뒷부분이 사라진다. */
  private static String requireWithinLimit(String content) {
    if (content != null && content.length() > CONTENT_MAX) {
      throw new CommonException(ErrorCode.DOCUMENT_EVENT_CONTENT_TOO_LONG);
    }
    return content;
  }
}
