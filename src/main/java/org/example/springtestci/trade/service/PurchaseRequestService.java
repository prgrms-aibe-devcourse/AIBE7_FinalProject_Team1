package org.example.springtestci.trade.service;

import java.util.Map;
import java.util.Optional;
import org.example.springtestci.chat.domain.ChatRoom;
import org.example.springtestci.chat.repository.ChatRoomRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.trade.dto.PurchaseRequestDirection;
import org.example.springtestci.trade.dto.PurchaseRequestPageResponse;
import org.example.springtestci.trade.dto.PurchaseRequestPageResponse.PageMetadata;
import org.example.springtestci.trade.dto.PurchaseRequestPageResponse.RoomContext;
import org.example.springtestci.trade.dto.PurchaseRequestResponse;
import org.example.springtestci.trade.entity.PurchaseRequest;
import org.example.springtestci.trade.entity.PurchaseRequestStatus;
import org.example.springtestci.trade.repository.PurchaseRequestRepository;
import org.example.springtestci.trade.repository.PurchaseRequestRepository.PageResult;
import org.example.springtestci.trade.repository.TradeAuditRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P2 구매 요청 생성·조회와 PENDING 요청의 취소·거절·수락을 처리한다.
 *
 * <p>수락은 제품→판매글→구매 요청 순으로 잠그고, 요청 RESERVED 전이·판매글 예약·INTERNAL/PENDING 이전 생성을 같은 트랜잭션에서 처리한다.
 * 비당사자에게는 거래 존재를 숨기기 위해 404 계약을 사용한다.
 */
@Service
@ConditionalOnProperty(
    prefix = "app.features.trade-purchase-requests",
    name = "enabled",
    havingValue = "true")
@Transactional(readOnly = true)
public class PurchaseRequestService {

  private final PurchaseRequestRepository requestRepository;
  private final ChatRoomRepository roomRepository;
  private final PublishedListingAccess listingAccess;
  private final InternalTransferPendingWriter transferWriter;
  private final TradeAuditRepository tradeAudit;

  public PurchaseRequestService(
      PurchaseRequestRepository requestRepository,
      ChatRoomRepository roomRepository,
      PublishedListingAccess listingAccess,
      InternalTransferPendingWriter transferWriter) {
    this(requestRepository, roomRepository, listingAccess, transferWriter, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PurchaseRequestService(
      PurchaseRequestRepository requestRepository,
      ChatRoomRepository roomRepository,
      PublishedListingAccess listingAccess,
      InternalTransferPendingWriter transferWriter,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          TradeAuditRepository tradeAudit) {
    this.requestRepository = requestRepository;
    this.roomRepository = roomRepository;
    this.listingAccess = listingAccess;
    this.transferWriter = transferWriter;
    this.tradeAudit = tradeAudit;
  }

  /** 구매 문의자만 요청할 수 있으며 같은 방의 기존 PENDING 요청은 멱등하게 반환한다. */
  @Transactional
  public CreateResult create(long listingId, long roomId, long actorId) {
    ChatRoom room = requireRoomParticipant(roomId, actorId);
    if (actorId != room.inquirerId()) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
    requireRoomListing(room, listingId);

    Optional<PurchaseRequest> existing = requestRepository.findPendingByRoomId(roomId);
    if (existing.isPresent()) {
      return new CreateResult(toResponse(existing.get()), false);
    }

    PublishedListing listing = requireLockedListing(listingId);
    existing = requestRepository.findPendingByRoomId(roomId);
    if (existing.isPresent()) {
      return new CreateResult(toResponse(existing.get(), listing.status()), false);
    }
    requirePurchasable(listing, room, actorId);

    PurchaseRequest saved = requestRepository.save(PurchaseRequest.pending(roomId, listingId));
    return new CreateResult(toResponse(saved, listing.status()), true);
  }

  /** 인증 사용자의 송수신 방향과 선택 필터에 맞는 요청·판매글 상태를 페이지 응답으로 조합한다. */
  public PurchaseRequestPageResponse findAll(
      long actorId,
      PurchaseRequestDirection direction,
      PurchaseRequestStatus status,
      Long roomId,
      int page,
      int size) {
    ChatRoom room = roomId == null ? null : requireRoomParticipant(roomId, actorId);
    PageResult result = requestRepository.findAll(actorId, direction, status, roomId, page, size);
    Map<Long, PublishedListingStatus> statuses =
        listingAccess.findStatuses(
            result.items().stream().map(PurchaseRequest::listingId).distinct().toList());
    int totalPages =
        result.totalElements() == 0 ? 0 : (int) ((result.totalElements() + size - 1) / size);
    RoomContext roomContext =
        room == null
            ? null
            : new RoomContext(
                room.roomId(), room.listingId(), statusesForRoom(statuses, room.listingId()));
    return new PurchaseRequestPageResponse(
        result.items().stream()
            .map(request -> toResponse(request, statuses.get(request.listingId())))
            .toList(),
        new PageMetadata(page, size, result.totalElements(), totalPages),
        roomContext);
  }

  /** 요청이 연결된 채팅방의 판매자 또는 문의자에게만 단건 상태를 반환한다. */
  public PurchaseRequestResponse findById(long requestId, long actorId) {
    PurchaseRequest request = requireRequest(requestId);
    requireRoomParticipant(request.roomId(), actorId);
    return toResponse(request);
  }

  /** 구매자가 아직 수락되지 않은 자신의 PENDING 요청만 취소한다. */
  @Transactional
  public PurchaseRequestResponse cancel(long requestId, long actorId) {
    PurchaseRequest request = requireLockedRequest(requestId);
    ChatRoom room = requireRoomParticipant(request.roomId(), actorId);
    if (actorId != room.inquirerId()) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
    String before = request.requestStatus().name();
    request.cancelPending();
    requestRepository.update(request);
    recordAudit(
        request.listingId(),
        actorId,
        requestId,
        "PURCHASE_REQUEST_CANCELLED",
        before,
        request.requestStatus().name(),
        "BUYER_CANCELLED");
    return toResponse(request);
  }

  /** 판매자가 자신의 판매글에 연결된 PENDING 요청을 거절한다. */
  @Transactional
  public PurchaseRequestResponse reject(long requestId, long actorId) {
    PurchaseRequest request = requireLockedRequest(requestId);
    ChatRoom room = requireRoomParticipant(request.roomId(), actorId);
    if (actorId != room.sellerId()) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
    String before = request.requestStatus().name();
    request.reject();
    requestRepository.update(request);
    recordAudit(
        request.listingId(),
        actorId,
        requestId,
        "PURCHASE_REQUEST_REJECTED",
        before,
        request.requestStatus().name(),
        "SELLER_REJECTED");
    return toResponse(request);
  }

  /**
   * 판매자가 요청을 수락해 예약을 확정한다.
   *
   * <p>{@link InternalTransferPendingWriter} 실패를 삼키지 않아 요청·판매글 변경도 함께 롤백한다.
   */
  @Transactional
  public PurchaseRequestResponse accept(long requestId, long actorId) {
    PurchaseRequest initial = requireRequest(requestId);
    ChatRoom room = requireRoomParticipant(initial.roomId(), actorId);
    if (actorId != room.sellerId()) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }

    // 모든 예약·취소 경로가 공유하는 제품→판매글→요청 잠금 순서를 유지한다.
    PublishedListing listing = requireLockedListing(initial.listingId());
    PurchaseRequest request = requireLockedRequest(requestId);
    requireRoomListing(room, request.listingId());
    requirePurchasable(listing, room, room.inquirerId());
    if (requestRepository.findReservedByListingId(request.listingId()).isPresent()) {
      throw new CommonException(ErrorCode.TRADE_CONFLICT);
    }

    request.accept();
    requestRepository.update(request);
    listingAccess.reserve(listing.listingId());
    transferWriter.createPending(
        InternalTransferPendingWriter.Command.internalPending(
            listing.productId(), request.id(), listing.sellerId(), room.inquirerId()));
    recordAudit(
        listing.productId(),
        actorId,
        requestId,
        "PURCHASE_REQUEST_ACCEPTED",
        "PENDING",
        "ACCEPTED",
        null);
    return toResponse(request, PublishedListingStatus.RESERVED);
  }

  private void recordAudit(
      long productIdOrListingId,
      Long actorId,
      long requestId,
      String action,
      String beforeFacts,
      String afterFacts,
      String reason) {
    if (tradeAudit == null) {
      return;
    }
    if ("PURCHASE_REQUEST_ACCEPTED".equals(action)) {
      tradeAudit.log(
          productIdOrListingId, actorId, requestId, action, beforeFacts, afterFacts, reason);
    } else {
      listingAccess
          .findById(productIdOrListingId)
          .ifPresent(
              listing ->
                  tradeAudit.log(
                      listing.productId(),
                      actorId,
                      requestId,
                      action,
                      beforeFacts,
                      afterFacts,
                      reason));
    }
  }

  /** 방이 없거나 당사자가 아니면 동일하게 404를 사용해 거래 존재를 노출하지 않는다. */
  private ChatRoom requireRoomParticipant(long roomId, long actorId) {
    ChatRoom room =
        roomRepository
            .findById(roomId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    if (actorId != room.sellerId() && actorId != room.inquirerId()) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return room;
  }

  private PurchaseRequest requireRequest(long requestId) {
    return requestRepository
        .findById(requestId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private PurchaseRequest requireLockedRequest(long requestId) {
    return requestRepository
        .findByIdForUpdate(requestId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private PublishedListing requireLockedListing(long listingId) {
    return listingAccess
        .findByIdForUpdate(listingId)
        .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
  }

  private void requireRoomListing(ChatRoom room, long listingId) {
    if (room.listingId() != listingId) {
      throw new CommonException(ErrorCode.TRADE_CONFLICT);
    }
  }

  /** 채팅방·판매글·현재 소유자·ACTIVE 상태와 선택 구매자 관계가 모두 일치하는지 검증한다. */
  private void requirePurchasable(PublishedListing listing, ChatRoom room, long buyerId) {
    if (listing.listingId() != room.listingId()
        || listing.sellerId() != room.sellerId()
        || listing.currentOwnerId() != listing.sellerId()
        || listing.status() != PublishedListingStatus.ACTIVE
        || buyerId != room.inquirerId()
        || buyerId == listing.sellerId()) {
      throw new CommonException(ErrorCode.TRADE_CONFLICT);
    }
  }

  private PurchaseRequestResponse toResponse(PurchaseRequest request) {
    PublishedListingStatus status =
        listingAccess.findById(request.listingId()).map(PublishedListing::status).orElse(null);
    return toResponse(request, status);
  }

  private PurchaseRequestResponse toResponse(
      PurchaseRequest request, PublishedListingStatus listingStatus) {
    return new PurchaseRequestResponse(
        request.id(),
        request.roomId(),
        request.listingId(),
        request.requestStatus(),
        request.transactionStatus(),
        listingStatus,
        request.cancelledById(),
        request.cancelReason(),
        request.cancelledAt());
  }

  private PublishedListingStatus statusesForRoom(
      Map<Long, PublishedListingStatus> statuses, long listingId) {
    if (statuses.containsKey(listingId)) {
      return statuses.get(listingId);
    }
    return listingAccess.findById(listingId).map(PublishedListing::status).orElse(null);
  }

  public record CreateResult(PurchaseRequestResponse response, boolean created) {}
}
