package org.example.springtestci.chat.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.example.springtestci.chat.dto.ChatMessageResponse;
import org.example.springtestci.chat.dto.ChatPageResponse;
import org.example.springtestci.chat.dto.ChatRoomResponse;
import org.example.springtestci.chat.dto.ChatRoomSummaryResponse;
import org.example.springtestci.chat.dto.ReadPositionResponse;
import org.example.springtestci.chat.dto.SendChatMessageRequest;
import org.example.springtestci.chat.dto.UpdateReadPositionRequest;
import org.example.springtestci.chat.service.ChatService;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * P1 채팅 HTTP 진입점이다.
 *
 * <p>인증 주체의 사용자 번호만 서비스에 전달하며 방 참여자 확인, 판매글 상태 및 메시지 처리 같은 업무 규칙은 {@link ChatService}가 담당한다.
 */
@Validated
@RestController
@RequestMapping("/api/v1")
@ConditionalOnProperty(prefix = "app.features.chat-api", name = "enabled", havingValue = "true")
public class ChatController {

  private static final String DEFAULT_PAGE = "0";
  private static final String DEFAULT_PAGE_SIZE = "20";
  private static final int MAX_PAGE_SIZE = 100;

  private final ChatService chatService;

  public ChatController(ChatService chatService) {
    this.chatService = chatService;
  }

  /** 경로의 게시 판매글 ID와 인증 사용자 ID를 서비스에 전달해 문의 방을 생성하거나 기존 방을 반환한다. */
  @PostMapping("/market/listings/{listingId}/chat-rooms")
  public ApiResponse<ChatRoomResponse> openRoom(
      @PathVariable @Positive Long listingId, Authentication authentication) {
    return ApiResponse.success(chatService.openRoom(listingId, actorId(authentication)));
  }

  /** 인증 참여자의 방만 조회한다. 선택적인 판매글 ID는 서비스의 읽기 전용 조회와 건수에 함께 적용한다. */
  @GetMapping("/chat-rooms")
  public ApiResponse<ChatPageResponse<ChatRoomSummaryResponse>> listMyRooms(
      Authentication authentication,
      @RequestParam(required = false) @Positive Long listingId,
      @RequestParam(defaultValue = DEFAULT_PAGE) @Min(0) int page,
      @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return ApiResponse.success(
        listingId == null
            ? chatService.listMyRooms(actorId(authentication), page, size)
            : chatService.listMyRooms(actorId(authentication), listingId, page, size));
  }

  /** 경로의 채팅방 ID에 대해 참여자 검증이 적용된 메시지 페이지를 반환한다. */
  @GetMapping("/chat-rooms/{roomId}/messages")
  public ApiResponse<ChatPageResponse<ChatMessageResponse>> listMessages(
      @PathVariable @Positive Long roomId,
      Authentication authentication,
      @RequestParam(defaultValue = DEFAULT_PAGE) @Min(0) int page,
      @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) @Min(1) @Max(MAX_PAGE_SIZE) int size) {
    return ApiResponse.success(
        chatService.listMessages(roomId, actorId(authentication), page, size));
  }

  /** 본문의 메시지를 서비스에 저장 위임하며 실시간 전달은 서비스의 커밋 이후 경로가 담당한다. */
  @PostMapping("/chat-rooms/{roomId}/messages")
  public ApiResponse<ChatMessageResponse> sendMessage(
      @PathVariable @Positive Long roomId,
      @Valid @RequestBody SendChatMessageRequest request,
      Authentication authentication) {
    return ApiResponse.success(
        chatService.sendMessage(roomId, actorId(authentication), request.content()));
  }

  /** 본문의 마지막 메시지 ID가 경로의 방에 속하는지 서비스에서 확인한 뒤 읽음 위치를 갱신한다. */
  @PutMapping("/chat-rooms/{roomId}/read-position")
  public ApiResponse<ReadPositionResponse> updateMyReadPosition(
      @PathVariable @Positive Long roomId,
      @Valid @RequestBody UpdateReadPositionRequest request,
      Authentication authentication) {
    return ApiResponse.success(
        chatService.updateMyReadPosition(
            roomId, actorId(authentication), request.lastReadMessageId()));
  }

  /** 인증 참여자가 채팅방을 나간다. 본인 목록에서 사라지고 상대방에게는 나갔다는 안내가 남는다. */
  @DeleteMapping("/chat-rooms/{roomId}")
  public ApiResponse<Void> leaveRoom(
      @PathVariable @Positive Long roomId, Authentication authentication) {
    chatService.leaveRoom(roomId, actorId(authentication));
    return ApiResponse.success(null);
  }

  /** Spring Security 인증 이름을 회원 ID로 변환하며 인증 누락·비숫자 주체는 401로 거부한다. */
  private long actorId(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    }

    try {
      return Long.parseLong(authentication.getName());
    } catch (NumberFormatException exception) {
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    }
  }
}
