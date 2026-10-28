package org.example.springtestci.trade.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.example.springtestci.chat.domain.ChatRoom;
import org.example.springtestci.chat.repository.ChatRoomRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.trade.dto.PurchaseRequestDirection;
import org.example.springtestci.trade.entity.PurchaseRequest;
import org.example.springtestci.trade.entity.PurchaseRequestStatus;
import org.example.springtestci.trade.entity.TransactionStatus;
import org.example.springtestci.trade.repository.PurchaseRequestRepository;
import org.example.springtestci.trade.repository.PurchaseRequestRepository.PageResult;
import org.example.springtestci.trade.repository.TradeAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PurchaseRequestServiceTest {

  private static final long REQUEST_ID = 41L;
  private static final long ROOM_ID = 51L;
  private static final long LISTING_ID = 61L;
  private static final long PRODUCT_ID = 71L;
  private static final long SELLER_ID = 81L;
  private static final long BUYER_ID = 91L;

  @Mock private PurchaseRequestRepository requestRepository;
  @Mock private ChatRoomRepository roomRepository;
  @Mock private PublishedListingAccess listingAccess;
  @Mock private InternalTransferPendingWriter transferWriter;
  @Mock private TradeAuditRepository tradeAudit;

  private PurchaseRequestService service;

  @BeforeEach
  void setUp() {
    service =
        new PurchaseRequestService(
            requestRepository, roomRepository, listingAccess, transferWriter, tradeAudit);
  }

  @Test
  void createsPendingRequestForRoomInquirer() {
    givenRoom();
    when(requestRepository.findPendingByRoomId(ROOM_ID)).thenReturn(Optional.empty());
    when(listingAccess.findByIdForUpdate(LISTING_ID)).thenReturn(Optional.of(activeListing()));
    when(requestRepository.save(any()))
        .thenReturn(
            PurchaseRequest.restore(
                REQUEST_ID, ROOM_ID, LISTING_ID, PurchaseRequestStatus.PENDING, null));

    PurchaseRequestService.CreateResult result = service.create(LISTING_ID, ROOM_ID, BUYER_ID);

    assertThat(result.created()).isTrue();
    assertThat(result.response().requestStatus()).isEqualTo(PurchaseRequestStatus.PENDING);
    assertThat(result.response().transactionStatus()).isNull();
  }

  @Test
  void reusesExistingPendingRequestWithoutLockingListing() {
    givenRoom();
    PurchaseRequest existing =
        PurchaseRequest.restore(
            REQUEST_ID, ROOM_ID, LISTING_ID, PurchaseRequestStatus.PENDING, null);
    when(requestRepository.findPendingByRoomId(ROOM_ID)).thenReturn(Optional.of(existing));
    when(listingAccess.findById(LISTING_ID))
        .thenReturn(
            Optional.of(
                new PublishedListing(
                    LISTING_ID,
                    PRODUCT_ID,
                    SELLER_ID,
                    SELLER_ID,
                    PublishedListingStatus.RESERVED)));

    PurchaseRequestService.CreateResult result = service.create(LISTING_ID, ROOM_ID, BUYER_ID);

    assertThat(result.created()).isFalse();
    assertThat(result.response().requestId()).isEqualTo(REQUEST_ID);
    assertThat(result.response().requestStatus()).isEqualTo(PurchaseRequestStatus.PENDING);
    assertThat(result.response().listingStatus()).isEqualTo(PublishedListingStatus.RESERVED);
    verify(listingAccess, never()).findByIdForUpdate(LISTING_ID);
    verify(requestRepository, never()).save(any());
  }

  @Test
  void hidesForeignRoomAsNotFound() {
    when(roomRepository.findById(ROOM_ID))
        .thenReturn(
            Optional.of(new ChatRoom(ROOM_ID, LISTING_ID, SELLER_ID, BUYER_ID, Instant.now())));

    assertThatThrownBy(() -> service.create(LISTING_ID, ROOM_ID, 999L))
        .isInstanceOf(CommonException.class);
  }

  @Test
  void acceptsRequestAndCreatesTransferForSelectedBuyer() {
    PurchaseRequest pending =
        PurchaseRequest.restore(
            REQUEST_ID, ROOM_ID, LISTING_ID, PurchaseRequestStatus.PENDING, null);
    when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    when(requestRepository.findReservedByListingId(LISTING_ID)).thenReturn(Optional.empty());
    givenRoom();
    when(listingAccess.findByIdForUpdate(LISTING_ID)).thenReturn(Optional.of(activeListing()));

    var response = service.accept(REQUEST_ID, SELLER_ID);

    assertThat(response.requestStatus()).isEqualTo(PurchaseRequestStatus.ACCEPTED);
    assertThat(response.transactionStatus()).isEqualTo(TransactionStatus.RESERVED);
    verify(listingAccess).reserve(LISTING_ID);
    ArgumentCaptor<InternalTransferPendingWriter.Command> command =
        ArgumentCaptor.forClass(InternalTransferPendingWriter.Command.class);
    verify(transferWriter).createPending(command.capture());
    assertThat(command.getValue().productId()).isEqualTo(PRODUCT_ID);
    assertThat(command.getValue().purchaseRequestId()).isEqualTo(REQUEST_ID);
    assertThat(command.getValue().fromUserId()).isEqualTo(SELLER_ID);
    assertThat(command.getValue().toUserId()).isEqualTo(BUYER_ID);
    assertThat(command.getValue().transferType()).isEqualTo("INTERNAL");
    assertThat(command.getValue().status()).isEqualTo("PENDING");
    verify(tradeAudit)
        .log(
            PRODUCT_ID,
            SELLER_ID,
            REQUEST_ID,
            "PURCHASE_REQUEST_ACCEPTED",
            "PENDING",
            "ACCEPTED",
            null);
  }

  @Test
  void rejectsSellerAttemptToCreateBuyerRequest() {
    givenRoom();

    assertThatThrownBy(() -> service.create(LISTING_ID, ROOM_ID, SELLER_ID))
        .isInstanceOf(CommonException.class);
    verify(requestRepository, never()).save(any());
  }

  @Test
  void listsSentRequestsWithFiltersAndListingStatuses() {
    PurchaseRequest pending = pendingRequest();
    when(requestRepository.findAll(
            BUYER_ID, PurchaseRequestDirection.SENT, PurchaseRequestStatus.PENDING, null, 1, 10))
        .thenReturn(new PageResult(List.of(pending), 11));
    when(listingAccess.findStatuses(List.of(LISTING_ID)))
        .thenReturn(Map.of(LISTING_ID, PublishedListingStatus.ACTIVE));

    var response =
        service.findAll(
            BUYER_ID, PurchaseRequestDirection.SENT, PurchaseRequestStatus.PENDING, null, 1, 10);

    assertThat(response.items()).hasSize(1);
    assertThat(response.items().get(0).listingStatus()).isEqualTo(PublishedListingStatus.ACTIVE);
    assertThat(response.page().number()).isEqualTo(1);
    assertThat(response.page().totalElements()).isEqualTo(11);
    assertThat(response.page().totalPages()).isEqualTo(2);
    assertThat(response.roomContext()).isNull();
  }

  @Test
  void returnsRoomContextForEmptyFilteredList() {
    givenRoom();
    when(requestRepository.findAll(
            SELLER_ID, PurchaseRequestDirection.RECEIVED, null, ROOM_ID, 0, 20))
        .thenReturn(new PageResult(List.of(), 0));
    when(listingAccess.findStatuses(List.of())).thenReturn(Map.of());
    when(listingAccess.findById(LISTING_ID))
        .thenReturn(
            Optional.of(
                new PublishedListing(
                    LISTING_ID,
                    PRODUCT_ID,
                    SELLER_ID,
                    SELLER_ID,
                    PublishedListingStatus.CANCELLED)));

    var response =
        service.findAll(SELLER_ID, PurchaseRequestDirection.RECEIVED, null, ROOM_ID, 0, 20);

    assertThat(response.items()).isEmpty();
    assertThat(response.roomContext().roomId()).isEqualTo(ROOM_ID);
    assertThat(response.roomContext().listingId()).isEqualTo(LISTING_ID);
    assertThat(response.roomContext().listingStatus()).isEqualTo(PublishedListingStatus.CANCELLED);
  }

  @Test
  void returnsDetailOnlyToRoomParticipant() {
    when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pendingRequest()));
    givenRoom();
    when(listingAccess.findById(LISTING_ID)).thenReturn(Optional.of(activeListing()));

    var response = service.findById(REQUEST_ID, SELLER_ID);

    assertThat(response.requestId()).isEqualTo(REQUEST_ID);
    assertThat(response.listingStatus()).isEqualTo(PublishedListingStatus.ACTIVE);

    assertError(ErrorCode.RESOURCE_NOT_FOUND, () -> service.findById(REQUEST_ID, 999L));
  }

  @Test
  void buyerCancelsAndSellerRejectsPendingRequests() {
    PurchaseRequest cancelled = pendingRequest();
    when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(cancelled));
    when(listingAccess.findById(LISTING_ID)).thenReturn(Optional.of(activeListing()));
    givenRoom();

    assertThat(service.cancel(REQUEST_ID, BUYER_ID).requestStatus())
        .isEqualTo(PurchaseRequestStatus.CANCELLED);
    verify(requestRepository).update(cancelled);
    verify(tradeAudit)
        .log(
            PRODUCT_ID,
            BUYER_ID,
            REQUEST_ID,
            "PURCHASE_REQUEST_CANCELLED",
            "PENDING",
            "CANCELLED",
            "BUYER_CANCELLED");

    PurchaseRequest rejected = pendingRequest();
    when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(rejected));
    assertThat(service.reject(REQUEST_ID, SELLER_ID).requestStatus())
        .isEqualTo(PurchaseRequestStatus.REJECTED);
    verify(requestRepository).update(rejected);
    verify(tradeAudit)
        .log(
            PRODUCT_ID,
            SELLER_ID,
            REQUEST_ID,
            "PURCHASE_REQUEST_REJECTED",
            "PENDING",
            "REJECTED",
            "SELLER_REJECTED");
  }

  @Test
  void rejectsWrongRolesAndReprocessingOfTerminatedRequest() {
    PurchaseRequest pending = pendingRequest();
    when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(pending));
    givenRoom();

    assertError(ErrorCode.ACCESS_DENIED, () -> service.cancel(REQUEST_ID, SELLER_ID));
    assertError(ErrorCode.ACCESS_DENIED, () -> service.reject(REQUEST_ID, BUYER_ID));

    PurchaseRequest terminated =
        PurchaseRequest.restore(
            REQUEST_ID, ROOM_ID, LISTING_ID, PurchaseRequestStatus.REJECTED, null);
    when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(terminated));
    assertError(ErrorCode.TRADE_CONFLICT, () -> service.cancel(REQUEST_ID, BUYER_ID));
    assertError(ErrorCode.TRADE_CONFLICT, () -> service.reject(REQUEST_ID, SELLER_ID));
  }

  @Test
  void rejectsBuyerAcceptanceWithoutChangingRequestOrDependencies() {
    PurchaseRequest pending = pendingRequest();
    when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(pending));
    givenRoom();

    assertError(ErrorCode.ACCESS_DENIED, () -> service.accept(REQUEST_ID, BUYER_ID));

    assertThat(pending.requestStatus()).isEqualTo(PurchaseRequestStatus.PENDING);
    assertThat(pending.transactionStatus()).isNull();
    verify(requestRepository, never()).update(any());
    verifyNoInteractions(listingAccess, transferWriter);
  }

  @Test
  void rejectsMismatchedRoomListingInactiveListingAndProductOwner() {
    when(roomRepository.findById(ROOM_ID))
        .thenReturn(Optional.of(new ChatRoom(ROOM_ID, 999L, SELLER_ID, BUYER_ID, Instant.now())));
    when(requestRepository.findPendingByRoomId(ROOM_ID)).thenReturn(Optional.empty());
    assertError(ErrorCode.TRADE_CONFLICT, () -> service.create(LISTING_ID, ROOM_ID, BUYER_ID));

    givenRoom();
    when(listingAccess.findByIdForUpdate(LISTING_ID))
        .thenReturn(
            Optional.of(
                new PublishedListing(
                    LISTING_ID, PRODUCT_ID, SELLER_ID, SELLER_ID, PublishedListingStatus.RESERVED)))
        .thenReturn(
            Optional.of(
                new PublishedListing(
                    LISTING_ID, PRODUCT_ID, SELLER_ID, 999L, PublishedListingStatus.ACTIVE)));
    assertError(ErrorCode.TRADE_CONFLICT, () -> service.create(LISTING_ID, ROOM_ID, BUYER_ID));
    assertError(ErrorCode.TRADE_CONFLICT, () -> service.create(LISTING_ID, ROOM_ID, BUYER_ID));
    verify(requestRepository, never()).save(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = PublishedListingStatus.class,
      names = {"RESERVED", "SOLD", "CANCELLED"})
  void rejectsNewRequestForEveryNonActiveListing(PublishedListingStatus listingStatus) {
    givenRoom();
    when(requestRepository.findPendingByRoomId(ROOM_ID)).thenReturn(Optional.empty());
    when(listingAccess.findByIdForUpdate(LISTING_ID))
        .thenReturn(
            Optional.of(
                new PublishedListing(LISTING_ID, PRODUCT_ID, SELLER_ID, SELLER_ID, listingStatus)));

    assertError(ErrorCode.TRADE_CONFLICT, () -> service.create(LISTING_ID, ROOM_ID, BUYER_ID));

    verify(requestRepository, never()).save(any());
  }

  @Test
  void rejectsListingWhoseFixedSellerDiffersFromRoomSeller() {
    givenRoom();
    when(requestRepository.findPendingByRoomId(ROOM_ID)).thenReturn(Optional.empty());
    when(listingAccess.findByIdForUpdate(LISTING_ID))
        .thenReturn(
            Optional.of(
                new PublishedListing(
                    LISTING_ID, PRODUCT_ID, 999L, 999L, PublishedListingStatus.ACTIVE)));

    assertError(ErrorCode.TRADE_CONFLICT, () -> service.create(LISTING_ID, ROOM_ID, BUYER_ID));
  }

  @Test
  void reservedRequestFromAnotherBuyerBlocksAcceptanceWithoutMutation() {
    PurchaseRequest selected = pendingRequest();
    PurchaseRequest reserved =
        PurchaseRequest.restore(
            42L, 52L, LISTING_ID, PurchaseRequestStatus.ACCEPTED, TransactionStatus.RESERVED);
    when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(selected));
    when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(selected));
    when(requestRepository.findReservedByListingId(LISTING_ID)).thenReturn(Optional.of(reserved));
    givenRoom();
    when(listingAccess.findByIdForUpdate(LISTING_ID)).thenReturn(Optional.of(activeListing()));

    assertError(ErrorCode.TRADE_CONFLICT, () -> service.accept(REQUEST_ID, SELLER_ID));

    assertThat(selected.requestStatus()).isEqualTo(PurchaseRequestStatus.PENDING);
    verify(requestRepository, never()).update(any());
    verify(listingAccess, never()).reserve(anyLong());
    verifyNoInteractions(transferWriter);
  }

  @Test
  void createsExplicitNewRequestAfterPreviousRequestTerminates() {
    givenRoom();
    when(requestRepository.findPendingByRoomId(ROOM_ID)).thenReturn(Optional.empty());
    when(listingAccess.findByIdForUpdate(LISTING_ID)).thenReturn(Optional.of(activeListing()));
    when(requestRepository.save(any()))
        .thenReturn(
            PurchaseRequest.restore(42L, ROOM_ID, LISTING_ID, PurchaseRequestStatus.PENDING, null));

    var result = service.create(LISTING_ID, ROOM_ID, BUYER_ID);

    assertThat(result.created()).isTrue();
    assertThat(result.response().requestId()).isEqualTo(42L);
  }

  @Test
  void repeatedAcceptanceOfCompletedRequestHasNoSideEffects() {
    PurchaseRequest accepted =
        PurchaseRequest.restore(
            REQUEST_ID,
            ROOM_ID,
            LISTING_ID,
            PurchaseRequestStatus.ACCEPTED,
            TransactionStatus.RESERVED);
    when(requestRepository.findById(REQUEST_ID)).thenReturn(Optional.of(accepted));
    when(requestRepository.findByIdForUpdate(REQUEST_ID)).thenReturn(Optional.of(accepted));
    when(requestRepository.findReservedByListingId(LISTING_ID)).thenReturn(Optional.empty());
    givenRoom();
    when(listingAccess.findByIdForUpdate(LISTING_ID)).thenReturn(Optional.of(activeListing()));

    assertError(ErrorCode.TRADE_CONFLICT, () -> service.accept(REQUEST_ID, SELLER_ID));

    verify(requestRepository, never()).update(any());
    verify(listingAccess, never()).reserve(anyLong());
    verifyNoInteractions(transferWriter);
  }

  private void givenRoom() {
    when(roomRepository.findById(ROOM_ID))
        .thenReturn(
            Optional.of(new ChatRoom(ROOM_ID, LISTING_ID, SELLER_ID, BUYER_ID, Instant.now())));
  }

  private PublishedListing activeListing() {
    return new PublishedListing(
        LISTING_ID, PRODUCT_ID, SELLER_ID, SELLER_ID, PublishedListingStatus.ACTIVE);
  }

  private PurchaseRequest pendingRequest() {
    return PurchaseRequest.restore(
        REQUEST_ID, ROOM_ID, LISTING_ID, PurchaseRequestStatus.PENDING, null);
  }

  private void assertError(ErrorCode errorCode, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            CommonException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
  }
}
