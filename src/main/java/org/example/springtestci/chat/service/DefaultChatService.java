package org.example.springtestci.chat.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.springtestci.chat.domain.ChatAccessPolicy;
import org.example.springtestci.chat.domain.ChatListingSnapshot;
import org.example.springtestci.chat.domain.ChatMessage;
import org.example.springtestci.chat.domain.ChatReadPosition;
import org.example.springtestci.chat.domain.ChatRoom;
import org.example.springtestci.chat.dto.ChatMessageResponse;
import org.example.springtestci.chat.dto.ChatPageResponse;
import org.example.springtestci.chat.dto.ChatPageResponse.PageMetadata;
import org.example.springtestci.chat.dto.ChatRoomResponse;
import org.example.springtestci.chat.dto.ChatRoomSummaryResponse;
import org.example.springtestci.chat.dto.ReadPositionResponse;
import org.example.springtestci.chat.repository.ChatMessageRepository;
import org.example.springtestci.chat.repository.ChatReadPositionRepository;
import org.example.springtestci.chat.repository.ChatRoomLeaveRepository;
import org.example.springtestci.chat.repository.ChatRoomRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P1 채팅 유스케이스 구현이다.
 *
 * <p>모든 방 접근에서 판매자·문의자 참여 여부를 확인한다. 쓰기 메서드만 읽기 전용 트랜잭션을 해제하며, 메시지 브로커 전달은 DB 커밋 이후로 지연한다.
 */
@Service
@ConditionalOnProperty(prefix = "app.features.chat-api", name = "enabled", havingValue = "true")
@Transactional(readOnly = true)
public class DefaultChatService implements ChatService {

  private static final Sort MESSAGE_SORT =
      Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

  private final ChatRoomRepository roomRepository;
  private final ChatMessageRepository messageRepository;
  private final ChatReadPositionRepository readPositionRepository;
  private final ChatRoomLeaveRepository leaveRepository;
  private final ChatListingReader listingReader;
  private final ChatUserReader userReader;
  private final ChatMessageDelivery messageDelivery;
  private final ChatAccessPolicy accessPolicy;

  public DefaultChatService(
      ChatRoomRepository roomRepository,
      ChatMessageRepository messageRepository,
      ChatReadPositionRepository readPositionRepository,
      ChatRoomLeaveRepository leaveRepository,
      ChatListingReader listingReader,
      ChatUserReader userReader,
      ChatMessageDelivery messageDelivery) {
    this.roomRepository = roomRepository;
    this.messageRepository = messageRepository;
    this.readPositionRepository = readPositionRepository;
    this.leaveRepository = leaveRepository;
    this.listingReader = listingReader;
    this.userReader = userReader;
    this.messageDelivery = messageDelivery;
    this.accessPolicy = new ChatAccessPolicy();
  }

  @Override
  @Transactional
  public ChatRoomResponse openRoom(long listingId, long actorId) {
    return roomRepository
        .findByListingIdAndInquirerId(listingId, actorId)
        .map(
            room -> {
              // 나갔던 문의자가 같은 글에서 다시 문의하면 본인 목록에 방을 되살린다.
              leaveRepository.clearLeft(room.roomId(), actorId);
              return toRoomResponse(room);
            })
        .orElseGet(() -> createRoom(listingId, actorId));
  }

  @Override
  public ChatPageResponse<ChatRoomSummaryResponse> listMyRooms(long actorId, int page, int size) {
    return listMyRooms(actorId, null, page, size);
  }

  /** 읽기 전용 트랜잭션에서 참여자·판매글 조건을 페이지 분할 전에 적용하고 결과 방의 요약만 일괄 조회한다. */
  @Override
  public ChatPageResponse<ChatRoomSummaryResponse> listMyRooms(
      long actorId, Long listingId, int page, int size) {
    if (listingId != null && listingId <= 0) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    Page<ChatRoom> rooms =
        listingId == null
            ? roomRepository.findByParticipantId(actorId, PageRequest.of(page, size))
            : roomRepository.findByParticipantIdAndListingId(
                actorId, listingId, PageRequest.of(page, size));
    // 내가 나간 방은 목록에서 숨긴다. 페이지 메타데이터는 DB 조회 기준이다.
    List<ChatRoom> visibleRooms =
        rooms.stream()
            .filter(room -> leaveRepository.findLeftAt(room.roomId(), actorId).isEmpty())
            .toList();
    List<Long> roomIds = visibleRooms.stream().map(ChatRoom::roomId).toList();
    Map<Long, ChatMessage> latestMessages =
        messageRepository.findLatestByRoomIds(roomIds).stream()
            .collect(Collectors.toMap(ChatMessage::roomId, Function.identity()));
    Map<Long, ChatListingSnapshot> listings =
        listingReader.findByIds(visibleRooms.stream().map(ChatRoom::listingId).toList());
    Set<Long> unreadRoomIds = messageRepository.findRoomIdsWithUnreadMessages(roomIds, actorId);
    Map<Long, String> nicknames =
        userReader.findNicknamesByIds(
            visibleRooms.stream().map(room -> counterpartyId(room, actorId)).distinct().toList());

    List<ChatRoomSummaryResponse> items =
        visibleRooms.stream()
            .map(
                room ->
                    toRoomSummary(
                        room,
                        actorId,
                        latestMessages.get(room.roomId()),
                        listings.get(room.listingId()),
                        nicknames,
                        unreadRoomIds.contains(room.roomId())))
            .toList();
    return page(items, rooms);
  }

  @Override
  public ChatPageResponse<ChatMessageResponse> listMessages(
      long roomId, long actorId, int page, int size) {
    ChatRoom room = requireActiveParticipant(roomId, actorId);
    Page<ChatMessage> messages =
        messageRepository.findByRoomId(roomId, PageRequest.of(page, size, MESSAGE_SORT));
    List<ChatMessageResponse> items = new ArrayList<>();
    // 상대방이 나갔다면 최신 위치(첫 페이지 맨 앞)에 저장되지 않는 안내 메시지를 덧붙인다.
    if (page == 0) {
      long counterpartyId = counterpartyId(room, actorId);
      leaveRepository
          .findLeftAt(roomId, counterpartyId)
          .ifPresent(leftAt -> items.add(leaveNotice(roomId, counterpartyId, leftAt)));
    }
    messages.stream().map(this::toMessageResponse).forEach(items::add);
    return page(items, messages);
  }

  @Override
  @Transactional(readOnly = true)
  public long findCounterpartyId(long roomId, long actorId) {
    return counterpartyId(requireActiveParticipant(roomId, actorId), actorId);
  }

  @Override
  @Transactional
  public ChatMessageResponse sendMessage(long roomId, long actorId, String content) {
    ChatRoom sendRoom = requireActiveParticipant(roomId, actorId);
    if (leaveRepository.findLeftAt(roomId, counterpartyId(sendRoom, actorId)).isPresent()) {
      // 상대방이 나간 방에는 더 이상 메시지를 보낼 수 없다.
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
    if (content == null || content.isBlank()) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    ChatMessageResponse response =
        toMessageResponse(messageRepository.append(roomId, actorId, content));
    // 롤백된 메시지가 구독자에게 먼저 보이지 않도록 실제 발행은 커밋 이후로 미룬다.
    messageDelivery.publishAfterCommit(response);
    return response;
  }

  /**
   * 나간 사람의 목록에서만 방을 숨긴다. DB의 방과 메시지는 삭제하지 않으며, 상대방은 나간 시각이 기록된 것을 보고 "나갔어요" 안내를 받는다. 이미 나갔다면 아무 일도
   * 하지 않는다.
   */
  @Override
  public void leaveRoom(long roomId, long actorId) {
    requireParticipant(roomId, actorId);
    leaveRepository.markLeft(roomId, actorId, Instant.now());
  }

  /** 저장하지 않는 시스템 안내 메시지다. messageId가 음수라 실제 메시지와 구분된다. */
  private ChatMessageResponse leaveNotice(long roomId, long leaverId, Instant leftAt) {
    String nickname =
        userReader.findNicknamesByIds(List.of(leaverId)).getOrDefault(leaverId, "상대방");
    return new ChatMessageResponse(-1L, roomId, leaverId, nickname + "님이 채팅방을 나갔어요.", leftAt, true);
  }

  @Override
  @Transactional
  public ReadPositionResponse updateMyReadPosition(
      long roomId, long actorId, long lastReadMessageId) {
    requireParticipant(roomId, actorId);
    ChatMessage message =
        messageRepository
            .findById(lastReadMessageId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    if (message.roomId() != roomId) {
      throw new CommonException(ErrorCode.INVALID_INPUT);
    }
    ChatReadPosition position = readPositionRepository.update(roomId, actorId, lastReadMessageId);
    return new ReadPositionResponse(position.roomId(), position.lastReadMessageId());
  }

  private ChatRoomResponse createRoom(long listingId, long actorId) {
    ChatListingSnapshot listing =
        listingReader
            .findById(listingId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!accessPolicy.canCreateRoom(listing, actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
    return toRoomResponse(roomRepository.create(listingId, listing.sellerId(), actorId));
  }

  private ChatRoom requireParticipant(long roomId, long actorId) {
    ChatRoom room =
        roomRepository
            .findById(roomId)
            .orElseThrow(() -> new CommonException(ErrorCode.RESOURCE_NOT_FOUND));
    if (!accessPolicy.canAccessRoom(room, actorId)) {
      throw new CommonException(ErrorCode.ACCESS_DENIED);
    }
    return room;
  }

  /** 이미 나간 방은 조회·전송 대상에서 제외한다. */
  private ChatRoom requireActiveParticipant(long roomId, long actorId) {
    ChatRoom room = requireParticipant(roomId, actorId);
    if (leaveRepository.findLeftAt(roomId, actorId).isPresent()) {
      throw new CommonException(ErrorCode.RESOURCE_NOT_FOUND);
    }
    return room;
  }

  private ChatRoomSummaryResponse toRoomSummary(
      ChatRoom room,
      long actorId,
      ChatMessage latestMessage,
      ChatListingSnapshot listing,
      Map<Long, String> nicknames,
      boolean hasUnread) {
    long counterpartyId = counterpartyId(room, actorId);
    return new ChatRoomSummaryResponse(
        room.roomId(),
        room.listingId(),
        nicknames.getOrDefault(counterpartyId, "사용자 #" + counterpartyId),
        listing == null ? "판매글 #" + room.listingId() : listing.title(),
        listing == null ? null : listing.status(),
        latestMessage == null ? null : latestMessage.content(),
        latestMessage == null ? null : latestMessage.createdAt(),
        hasUnread);
  }

  private long counterpartyId(ChatRoom room, long actorId) {
    return actorId == room.sellerId() ? room.inquirerId() : room.sellerId();
  }

  private ChatRoomResponse toRoomResponse(ChatRoom room) {
    return new ChatRoomResponse(room.roomId(), room.listingId());
  }

  private ChatMessageResponse toMessageResponse(ChatMessage message) {
    return new ChatMessageResponse(
        message.messageId(),
        message.roomId(),
        message.senderId(),
        message.content(),
        message.createdAt());
  }

  private <T> ChatPageResponse<T> page(List<T> items, Page<?> source) {
    return new ChatPageResponse<>(
        items,
        new PageMetadata(
            source.getNumber(),
            source.getSize(),
            source.getTotalElements(),
            source.getTotalPages(),
            source.hasNext()));
  }
}
