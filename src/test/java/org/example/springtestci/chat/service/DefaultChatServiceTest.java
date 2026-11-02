package org.example.springtestci.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.example.springtestci.chat.domain.ChatListingSnapshot;
import org.example.springtestci.chat.domain.ChatListingStatus;
import org.example.springtestci.chat.domain.ChatMessage;
import org.example.springtestci.chat.domain.ChatReadPosition;
import org.example.springtestci.chat.domain.ChatRoom;
import org.example.springtestci.chat.dto.ChatMessageResponse;
import org.example.springtestci.chat.dto.ChatRoomResponse;
import org.example.springtestci.chat.dto.ChatRoomSummaryResponse;
import org.example.springtestci.chat.repository.ChatMessageRepository;
import org.example.springtestci.chat.repository.ChatReadPositionRepository;
import org.example.springtestci.chat.repository.ChatRoomLeaveRepository;
import org.example.springtestci.chat.repository.ChatRoomRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class DefaultChatServiceTest {

  private static final long SELLER_ID = 10L;
  private static final long BUYER_ID = 20L;
  private static final long OUTSIDER_ID = 30L;
  private static final long LISTING_ID = 40L;
  private static final long ROOM_ID = 50L;
  private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

  @Mock private ChatRoomRepository roomRepository;
  @Mock private ChatMessageRepository messageRepository;
  @Mock private ChatReadPositionRepository readPositionRepository;
  @Mock private ChatRoomLeaveRepository leaveRepository;
  @Mock private ChatListingReader listingReader;
  @Mock private ChatUserReader userReader;
  @Mock private ChatMessageDelivery messageDelivery;

  private DefaultChatService service;

  @BeforeEach
  void setUp() {
    service =
        new DefaultChatService(
            roomRepository,
            messageRepository,
            readPositionRepository,
            leaveRepository,
            listingReader,
            userReader,
            messageDelivery);
  }

  @Test
  void reusesExistingRoomBeforeCheckingCurrentListingState() {
    ChatRoom room = room();
    when(roomRepository.findByListingIdAndInquirerId(LISTING_ID, BUYER_ID))
        .thenReturn(Optional.of(room));

    ChatRoomResponse response = service.openRoom(LISTING_ID, BUYER_ID);

    assertEquals(ROOM_ID, response.roomId());
    verify(listingReader, never()).findById(LISTING_ID);
    verify(roomRepository, never()).create(LISTING_ID, SELLER_ID, BUYER_ID);
  }

  @Test
  void createsRoomForAnotherUsersActiveOwnedListing() {
    when(roomRepository.findByListingIdAndInquirerId(LISTING_ID, BUYER_ID))
        .thenReturn(Optional.empty());
    when(listingReader.findById(LISTING_ID)).thenReturn(Optional.of(activeListing()));
    when(roomRepository.create(LISTING_ID, SELLER_ID, BUYER_ID)).thenReturn(room());

    ChatRoomResponse response = service.openRoom(LISTING_ID, BUYER_ID);

    assertEquals(ROOM_ID, response.roomId());
    verify(roomRepository).create(LISTING_ID, SELLER_ID, BUYER_ID);
  }

  @Test
  void rejectsNewRoomForInactiveListing() {
    ChatListingSnapshot sold =
        new ChatListingSnapshot(
            LISTING_ID, SELLER_ID, 1L, SELLER_ID, ChatListingStatus.SOLD, "노트북");
    when(roomRepository.findByListingIdAndInquirerId(LISTING_ID, BUYER_ID))
        .thenReturn(Optional.empty());
    when(listingReader.findById(LISTING_ID)).thenReturn(Optional.of(sold));

    CommonException exception =
        assertThrows(CommonException.class, () -> service.openRoom(LISTING_ID, BUYER_ID));

    assertEquals(ErrorCode.ACCESS_DENIED, exception.errorCode());
    verify(roomRepository, never()).create(LISTING_ID, SELLER_ID, BUYER_ID);
  }

  @Test
  void rejectsOutsiderFromListingMessages() {
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));

    CommonException listException =
        assertThrows(
            CommonException.class, () -> service.listMessages(ROOM_ID, OUTSIDER_ID, 0, 20));
    CommonException sendException =
        assertThrows(
            CommonException.class, () -> service.sendMessage(ROOM_ID, OUTSIDER_ID, "blocked"));
    CommonException readException =
        assertThrows(
            CommonException.class, () -> service.updateMyReadPosition(ROOM_ID, OUTSIDER_ID, 60L));

    assertEquals(ErrorCode.ACCESS_DENIED, listException.errorCode());
    assertEquals(ErrorCode.ACCESS_DENIED, sendException.errorCode());
    assertEquals(ErrorCode.ACCESS_DENIED, readException.errorCode());
    verify(messageRepository, never()).append(ROOM_ID, OUTSIDER_ID, "blocked");
    verify(messageRepository, never()).findById(60L);
    verify(readPositionRepository, never()).update(ROOM_ID, OUTSIDER_ID, 60L);
  }

  @ParameterizedTest(name = "existing room remains available when listing is {0}")
  @EnumSource(value = ChatListingStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
  void existingParticipantsCanReuseReadAndSendForInactiveListings(ChatListingStatus status) {
    ChatRoom room = room();
    ChatMessage saved = new ChatMessage(60L, ROOM_ID, SELLER_ID, "reply", NOW);
    ChatListingSnapshot inactiveListing =
        new ChatListingSnapshot(LISTING_ID, SELLER_ID, 1L, SELLER_ID, status, "not active");
    lenient().when(listingReader.findById(LISTING_ID)).thenReturn(Optional.of(inactiveListing));
    when(roomRepository.findByListingIdAndInquirerId(LISTING_ID, BUYER_ID))
        .thenReturn(Optional.of(room));
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
    when(messageRepository.findByRoomId(eq(ROOM_ID), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));
    when(messageRepository.append(ROOM_ID, SELLER_ID, "reply")).thenReturn(saved);

    assertEquals(ROOM_ID, service.openRoom(LISTING_ID, BUYER_ID).roomId());
    assertTrue(service.listMessages(ROOM_ID, BUYER_ID, 0, 20).items().isEmpty());
    assertEquals(SELLER_ID, service.sendMessage(ROOM_ID, SELLER_ID, "reply").senderId());
    assertNotEquals(ChatListingStatus.ACTIVE, status);
    verify(listingReader, never()).findById(LISTING_ID);
  }

  @Test
  void savesAuthenticatedParticipantAsSenderWithoutChangingContent() {
    ChatMessage saved = new ChatMessage(60L, ROOM_ID, BUYER_ID, " 원문 ", NOW);
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));
    when(messageRepository.append(ROOM_ID, BUYER_ID, " 원문 ")).thenReturn(saved);

    ChatMessageResponse response = service.sendMessage(ROOM_ID, BUYER_ID, " 원문 ");

    assertEquals(BUYER_ID, response.senderId());
    assertEquals(" 원문 ", response.content());
    verify(messageDelivery).publishAfterCommit(response);
  }

  @Test
  void rejectsBlankContentForDirectServiceCalls() {
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));

    CommonException exception =
        assertThrows(CommonException.class, () -> service.sendMessage(ROOM_ID, BUYER_ID, " "));

    assertEquals(ErrorCode.INVALID_INPUT, exception.errorCode());
    verify(messageRepository, never()).append(ROOM_ID, BUYER_ID, " ");
    verify(messageDelivery, never()).publishAfterCommit(any());
  }

  @Test
  void rejectsReadPositionMessageFromAnotherRoom() {
    ChatMessage otherRoomMessage = new ChatMessage(60L, 999L, SELLER_ID, "답장", NOW);
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));
    when(messageRepository.findById(60L)).thenReturn(Optional.of(otherRoomMessage));

    CommonException exception =
        assertThrows(
            CommonException.class, () -> service.updateMyReadPosition(ROOM_ID, BUYER_ID, 60L));

    assertEquals(ErrorCode.INVALID_INPUT, exception.errorCode());
    verify(readPositionRepository, never()).update(ROOM_ID, BUYER_ID, 60L);
  }

  @Test
  void roomListContainsLatestMessageAndPersonalUnreadState() {
    ChatRoom room = room();
    ChatMessage latest = new ChatMessage(60L, ROOM_ID, SELLER_ID, "답장", NOW);
    PageRequest pageable = PageRequest.of(0, 20);
    when(roomRepository.findByParticipantId(BUYER_ID, pageable))
        .thenReturn(new PageImpl<>(List.of(room), pageable, 1));
    when(messageRepository.findLatestByRoomIds(List.of(ROOM_ID))).thenReturn(List.of(latest));
    when(listingReader.findByIds(List.of(LISTING_ID)))
        .thenReturn(Map.of(LISTING_ID, activeListing()));
    when(userReader.findNicknamesByIds(List.of(SELLER_ID))).thenReturn(Map.of(SELLER_ID, "판매자"));
    when(messageRepository.findRoomIdsWithUnreadMessages(List.of(ROOM_ID), BUYER_ID))
        .thenReturn(Set.of(ROOM_ID));

    ChatRoomSummaryResponse summary = service.listMyRooms(BUYER_ID, 0, 20).items().get(0);

    assertEquals("판매자", summary.counterpartyNickname());
    assertEquals("노트북", summary.listingTitle());
    assertEquals(ChatListingStatus.ACTIVE, summary.listingStatus());
    assertEquals("답장", summary.latestMessagePreview());
    assertTrue(summary.hasUnreadMessages());
  }

  @Test
  void readPositionUsesOnlyAuthenticatedParticipant() {
    ChatMessage message = new ChatMessage(60L, ROOM_ID, SELLER_ID, "답장", NOW);
    ChatReadPosition position = new ChatReadPosition(ROOM_ID, BUYER_ID, 60L, NOW);
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));
    when(messageRepository.findById(60L)).thenReturn(Optional.of(message));
    when(readPositionRepository.update(ROOM_ID, BUYER_ID, 60L)).thenReturn(position);

    assertEquals(60L, service.updateMyReadPosition(ROOM_ID, BUYER_ID, 60L).lastReadMessageId());
  }

  @Test
  void noOpposingMessageMeansNoUnreadState() {
    ChatRoom room = room();
    PageRequest pageable = PageRequest.of(0, 20);
    when(roomRepository.findByParticipantId(BUYER_ID, pageable))
        .thenReturn(new PageImpl<>(List.of(room), pageable, 1));
    when(messageRepository.findLatestByRoomIds(List.of(ROOM_ID))).thenReturn(List.of());
    when(listingReader.findByIds(List.of(LISTING_ID))).thenReturn(Map.of());
    when(userReader.findNicknamesByIds(List.of(SELLER_ID))).thenReturn(Map.of());
    when(messageRepository.findRoomIdsWithUnreadMessages(List.of(ROOM_ID), BUYER_ID))
        .thenReturn(Set.of());

    ChatRoomSummaryResponse summary = service.listMyRooms(BUYER_ID, 0, 20).items().get(0);

    assertFalse(summary.hasUnreadMessages());
    assertEquals("사용자 #10", summary.counterpartyNickname());
    assertNull(summary.listingStatus());
  }

  private ChatRoom room() {
    return new ChatRoom(ROOM_ID, LISTING_ID, SELLER_ID, BUYER_ID, NOW);
  }

  @Test
  void listingFilterUsesParticipantRepositoryBeforeBuildingSummaries() {
    PageRequest pageable = PageRequest.of(0, 20);
    when(roomRepository.findByParticipantIdAndListingId(BUYER_ID, LISTING_ID, pageable))
        .thenReturn(new PageImpl<>(List.of(room()), pageable, 1));
    var response = service.listMyRooms(BUYER_ID, LISTING_ID, 0, 20);
    assertEquals(LISTING_ID, response.items().get(0).listingId());
    verify(roomRepository).findByParticipantIdAndListingId(BUYER_ID, LISTING_ID, pageable);
    verify(roomRepository, never()).findByParticipantId(BUYER_ID, pageable);
  }

  @Test
  void rejectsNonPositiveListingFilter() {
    CommonException exception =
        assertThrows(CommonException.class, () -> service.listMyRooms(BUYER_ID, 0L, 0, 20));
    assertEquals(ErrorCode.INVALID_INPUT, exception.errorCode());
  }

  @Test
  void leavingRoomOnlyRecordsLeaveTimeAndDeletesNothing() {
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));

    service.leaveRoom(ROOM_ID, BUYER_ID);

    verify(leaveRepository).markLeft(eq(ROOM_ID), eq(BUYER_ID), any(Instant.class));
    verify(messageRepository, never()).append(anyLong(), anyLong(), any());
  }

  @Test
  void outsiderCannotLeaveRoom() {
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));

    CommonException exception =
        assertThrows(CommonException.class, () -> service.leaveRoom(ROOM_ID, OUTSIDER_ID));

    assertEquals(ErrorCode.ACCESS_DENIED, exception.errorCode());
    verify(leaveRepository, never()).markLeft(anyLong(), anyLong(), any());
  }

  @Test
  void counterpartySeesLeaveNoticeOnFirstMessagePage() {
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));
    when(leaveRepository.findLeftAt(ROOM_ID, SELLER_ID)).thenReturn(Optional.empty());
    when(leaveRepository.findLeftAt(ROOM_ID, BUYER_ID)).thenReturn(Optional.of(NOW));
    when(userReader.findNicknamesByIds(List.of(BUYER_ID))).thenReturn(Map.of(BUYER_ID, "구매자"));
    when(messageRepository.findByRoomId(eq(ROOM_ID), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    var page = service.listMessages(ROOM_ID, SELLER_ID, 0, 20);

    assertEquals(1, page.items().size());
    assertTrue(page.items().get(0).system());
    assertEquals("구매자님이 채팅방을 나갔어요.", page.items().get(0).content());
  }

  @Test
  void leaverCannotReadRoomAnymore() {
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));
    when(leaveRepository.findLeftAt(ROOM_ID, BUYER_ID)).thenReturn(Optional.of(NOW));

    assertThrows(CommonException.class, () -> service.listMessages(ROOM_ID, BUYER_ID, 0, 20));
  }

  @Test
  void cannotSendMessageAfterCounterpartyLeft() {
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));
    when(leaveRepository.findLeftAt(ROOM_ID, BUYER_ID)).thenReturn(Optional.empty());
    when(leaveRepository.findLeftAt(ROOM_ID, SELLER_ID)).thenReturn(Optional.of(NOW));

    assertThrows(CommonException.class, () -> service.sendMessage(ROOM_ID, BUYER_ID, "안녕"));

    verify(messageRepository, never()).append(anyLong(), anyLong(), any());
  }

  @Test
  void participantsGetOnlyTheirCounterpartyIdAndOutsidersAreDenied() {
    when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room()));

    assertEquals(BUYER_ID, service.findCounterpartyId(ROOM_ID, SELLER_ID));
    assertEquals(SELLER_ID, service.findCounterpartyId(ROOM_ID, BUYER_ID));
    CommonException exception =
        assertThrows(CommonException.class, () -> service.findCounterpartyId(ROOM_ID, OUTSIDER_ID));
    assertEquals(ErrorCode.ACCESS_DENIED, exception.errorCode());
  }

  private ChatListingSnapshot activeListing() {
    return new ChatListingSnapshot(
        LISTING_ID, SELLER_ID, 1L, SELLER_ID, ChatListingStatus.ACTIVE, "노트북");
  }
}
