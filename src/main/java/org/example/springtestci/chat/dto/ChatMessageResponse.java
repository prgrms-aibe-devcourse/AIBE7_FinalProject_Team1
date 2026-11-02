package org.example.springtestci.chat.dto;

import java.time.Instant;

/** 메시지 응답이다. {@code system}이 true면 화면에서 가운데 정렬 안내 문구로 그린다. */
public record ChatMessageResponse(
    long messageId, long roomId, long senderId, String content, Instant createdAt, boolean system) {

  /** 일반 참여자 메시지용 생성자다. */
  public ChatMessageResponse(
      long messageId, long roomId, long senderId, String content, Instant createdAt) {
    this(messageId, roomId, senderId, content, createdAt, false);
  }

  @Override
  public String toString() {
    return "ChatMessageResponse[messageId="
        + messageId
        + ", roomId="
        + roomId
        + ", senderId="
        + senderId
        + ", content=<redacted>, createdAt="
        + createdAt
        + ", system="
        + system
        + "]";
  }
}
