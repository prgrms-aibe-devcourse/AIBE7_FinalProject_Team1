package org.example.springtestci.listing.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.dto.DraftProductResponse;
import org.example.springtestci.listing.dto.ListingDraftPageResponse;
import org.example.springtestci.listing.dto.ListingDraftResponse;
import org.example.springtestci.listing.dto.SaveListingDraftRequest;
import org.example.springtestci.listing.entity.ListingDraft;
import org.example.springtestci.listing.repository.ListingDraftQueryRepository;
import org.example.springtestci.listing.repository.ListingDraftRepository;
import org.example.springtestci.ownership.event.OwnershipTransferred;
import org.example.springtestci.product.dto.ProductPageResponse;
import org.example.springtestci.product.service.ProductService;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 제품 소유권·초안 작성자·버전·사진 소속을 확인한 뒤 비공개 초안을 저장하거나 불러온다. */
@Service
@Transactional(readOnly = true)
public class ListingDraftService {

  private final ListingDraftRepository drafts;
  private final ProductService products;
  private final ListingDraftQueryRepository draftQueries;

  public ListingDraftService(
      ListingDraftRepository drafts,
      ProductService products,
      ListingDraftQueryRepository draftQueries) {
    this.drafts = drafts;
    this.products = products;
    this.draftQueries = draftQueries;
  }

  /** 판매 초안 선택기에서 누락되는 제품이 없도록 소유 제품의 모든 페이지를 순서대로 읽는다. */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<DraftProductResponse> products(long userId) {
    List<DraftProductResponse> result = new ArrayList<>();
    int page = 0;
    ProductPageResponse.Cursor cursor = null;
    do {
      var response =
          products.listMine(
              userId,
              page,
              100,
              cursor == null ? null : cursor.createdAt(),
              cursor == null ? null : cursor.productId());
      response.items().stream().map(DraftProductResponse::from).forEach(result::add);
      cursor = response.page().nextCursor();
      page += 1;
    } while (cursor != null);
    return List.copyOf(result);
  }

  public DraftProductResponse composition(long productId, long userId) {
    return DraftProductResponse.from(products.getForListing(userId, productId));
  }

  public ListingDraftPageResponse list(long userId, int page, int size) {
    return ListingDraftPageResponse.from(
        draftQueries.findAvailableTo(userId, PageRequest.of(page, size)));
  }

  /** 소유권 이전·수정과 경합해도 검사한 소유권과 초안 내용을 같은 잠금 안에서 읽는다. */
  @Transactional
  public ListingDraftResponse get(long productId, long userId) {
    products.lockOwnedForListing(userId, productId);
    return ListingDraftResponse.from(ownedDraft(productId, userId));
  }

  /** 제품당 초안 한 개만 생성한다. 기존 초안이 있으면 수정 API를 사용해야 한다. */
  @Transactional
  public ListingDraftResponse save(long productId, long userId, SaveListingDraftRequest request) {
    products.lockOwnedForListing(userId, productId);
    if (request.version() != null || drafts.findByProductId(productId).isPresent()) {
      throw new CommonException(ErrorCode.DRAFT_CONFLICT);
    }
    validateImages(productId, userId, request);
    ListingDraft draft = new ListingDraft(productId, userId);
    draft.replaceContent(request);
    drafts.saveAndFlush(draft);
    return ListingDraftResponse.from(draft);
  }

  /** 화면에서 읽었던 버전이 DB의 현재 버전과 같을 때만 내용을 교체한다. */
  @Transactional
  public ListingDraftResponse update(
      long productId, long draftId, long userId, SaveListingDraftRequest request) {
    products.lockOwnedForListing(userId, productId);
    ListingDraft draft = ownedDraft(productId, userId);
    requireCurrentVersion(draft, draftId, request.version());
    validateImages(productId, userId, request);
    draft.replaceContent(request);
    drafts.saveAndFlush(draft);
    return ListingDraftResponse.from(draft);
  }

  @Transactional
  public void delete(long productId, long draftId, long userId, long version) {
    products.lockOwnedForListing(userId, productId);
    ListingDraft draft = ownedDraft(productId, userId);
    requireCurrentVersion(draft, draftId, version);
    drafts.delete(draft);
    drafts.flush();
  }

  /** 이전 트랜잭션의 제품 잠금 안에서 예전 작성자의 초안과 사진 선택만 삭제한다. 제품 사진은 유지한다. */
  @EventListener
  @Transactional(propagation = Propagation.MANDATORY)
  public void ownershipTransferred(OwnershipTransferred event) {
    // AFTER_COMMIT으로 미루면 새 소유자의 저장과 경합한다. 현재 소유권을 다시 확인하고 동기 처리한다.
    products.lockOwnedForListing(event.newOwnerId(), event.productId());
    drafts
        .findByProductId(event.productId())
        .filter(draft -> draft.getAuthorId() != event.newOwnerId())
        .ifPresent(
            draft -> {
              drafts.delete(draft);
              drafts.flush();
            });
  }

  private ListingDraft ownedDraft(long productId, long userId) {
    return drafts
        .findByProductId(productId)
        .filter(draft -> draft.getAuthorId() == userId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  // 삭제 후 새로 생성된 초안이나 다른 탭에서 수정한 초안을 예전 화면으로 덮어쓰지 못하게 한다.
  private void requireCurrentVersion(ListingDraft draft, long draftId, Long version) {
    if (draft.getId() != draftId || version == null || !version.equals(draft.getVersion())) {
      throw new CommonException(ErrorCode.DRAFT_CONFLICT);
    }
  }

  // 클라이언트가 보낸 사진 ID도 신뢰하지 않고 해당 제품의 사용 가능한 사진인지 다시 확인한다.
  private void validateImages(long productId, long userId, SaveListingDraftRequest request) {
    List<Long> imageIds = request.imageIds();
    if (new HashSet<>(imageIds).size() != imageIds.size())
      throw new CommonException(ErrorCode.INVALID_INPUT);
    var allowed = new HashSet<Long>();
    products
        .getForListing(userId, productId)
        .images()
        .forEach(image -> allowed.add(image.imageId()));
    if (!allowed.containsAll(imageIds)) throw new CommonException(ErrorCode.INVALID_INPUT);
  }

  public DraftImage image(long productId, long imageId, long userId) throws IOException {
    var image = products.image(userId, productId, imageId);
    return new DraftImage(image.resource(), image.contentType());
  }

  public record DraftImage(Resource resource, String contentType) {}
}
