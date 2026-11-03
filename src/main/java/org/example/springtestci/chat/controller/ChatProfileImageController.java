package org.example.springtestci.chat.controller;

import org.example.springtestci.auth.dto.ProfileImageContent;
import org.example.springtestci.auth.service.ProfileImageService;
import org.example.springtestci.chat.service.ChatService;
import org.example.springtestci.common.security.CurrentUserIdResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 채팅방의 상대방 프로필 사진을 돌려준다. 방 참여자만 그 방의 상대방 사진을 볼 수 있고, 회원 id를 직접 받지 않으므로 임의 회원의 사진을 조회할 수 없다. */
@RestController
@ConditionalOnProperty(prefix = "app.features.chat-api", name = "enabled", havingValue = "true")
@RequestMapping("/api/v1/chat-rooms/{roomId}/counterparty/profile-image")
public class ChatProfileImageController {

  private final ChatService chatService;
  private final ProfileImageService profileImageService;
  private final CurrentUserIdResolver currentUserIdResolver;

  /** 채팅 서비스(참여 확인), 프로필 사진 서비스와 로그인 회원 식별기를 주입받는다. */
  public ChatProfileImageController(
      ChatService chatService,
      ProfileImageService profileImageService,
      CurrentUserIdResolver currentUserIdResolver) {
    this.chatService = chatService;
    this.profileImageService = profileImageService;
    this.currentUserIdResolver = currentUserIdResolver;
  }

  /** 상대방 사진을 이미지로 돌려준다. 사진이 없으면 204, 방 참여자가 아니면 오류 응답이다. */
  @GetMapping
  public ResponseEntity<byte[]> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable("roomId") long roomId) {
    long actorId = currentUserIdResolver.resolve(jwt);
    long counterpartyId = chatService.findCounterpartyId(roomId, actorId);
    return profileImageService
        .find(counterpartyId)
        .map(this::image)
        .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
  }

  private ResponseEntity<byte[]> image(ProfileImageContent image) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(image.contentType()))
        .cacheControl(CacheControl.noCache().cachePrivate())
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .header("X-Content-Type-Options", "nosniff")
        .body(image.content());
  }
}
