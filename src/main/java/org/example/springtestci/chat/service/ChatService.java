package org.example.springtestci.chat.service;

import org.example.springtestci.chat.dto.ChatMessageResponse;
import org.example.springtestci.chat.dto.ChatPageResponse;
import org.example.springtestci.chat.dto.ChatRoomResponse;
import org.example.springtestci.chat.dto.ChatRoomSummaryResponse;
import org.example.springtestci.chat.dto.ReadPositionResponse;

/**
 * 인증 사용자의 채팅방·메시지·읽음 위치 유스케이스 계약이다.
 *
 * @see DefaultChatService
 */
public interface ChatService {

  /** 판매글 구매 문의자용 방을 생성하거나 기존 방을 멱등하게 반환한다. */
  ChatRoomResponse openRoom(long listingId, long actorId);

  /** 사용자가 판매자 또는 문의자로 참여한 방만 페이지로 조회한다. */
  ChatPageResponse<ChatRoomSummaryResponse> listMyRooms(long actorId, int page, int size);

  /** 인증 참여자의 방을 판매글 ID로 제한한다. 존재하지 않거나 참여하지 않은 글은 빈 페이지다. */
  ChatPageResponse<ChatRoomSummaryResponse> listMyRooms(
      long actorId, Long listingId, int page, int size);

  /** 방 참여자에게만 메시지 내역을 반환한다. */
  ChatPageResponse<ChatMessageResponse> listMessages(long roomId, long actorId, int page, int size);

  /** 참여자 메시지를 저장하고 커밋 이후 실시간 전달을 예약한다. */
  ChatMessageResponse sendMessage(long roomId, long actorId, String content);

  /** 방 참여자에게만 상대방의 회원 id를 돌려준다. 방이 없으면 404, 참여자가 아니거나 이미 나간 방이면 접근 거부다. */
  long findCounterpartyId(long roomId, long actorId);

  /** 참여자가 채팅방을 나간다. 본인 목록에서 방이 사라지고, 상대방에게는 "나갔어요" 시스템 메시지가 남는다. 이미 나갔다면 아무 일도 하지 않는다. */
  void leaveRoom(long roomId, long actorId);

  /** 참여자가 실제 해당 방에 속한 메시지까지 읽었음을 기록한다. */
  ReadPositionResponse updateMyReadPosition(long roomId, long actorId, long lastReadMessageId);
}
