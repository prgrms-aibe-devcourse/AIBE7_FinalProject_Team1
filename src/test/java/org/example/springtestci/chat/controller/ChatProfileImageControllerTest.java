package org.example.springtestci.chat.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.example.springtestci.auth.dto.ProfileImageContent;
import org.example.springtestci.auth.service.ProfileImageService;
import org.example.springtestci.chat.service.ChatService;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.security.CurrentUserIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ChatProfileImageControllerTest {

  private final ChatService chatService = mock(ChatService.class);
  private final ProfileImageService images = mock(ProfileImageService.class);
  private final ChatProfileImageController controller =
      new ChatProfileImageController(chatService, images, new CurrentUserIdResolver());

  private static Jwt jwt(long userId) {
    return Jwt.withTokenValue("t")
        .header("alg", "none")
        .subject(String.valueOf(userId))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }

  @Test
  void 방_참여자는_상대방의_사진만_받는다() {
    when(chatService.findCounterpartyId(5L, 1L)).thenReturn(2L);
    when(images.find(2L))
        .thenReturn(
            Optional.of(new ProfileImageContent(new byte[] {7}, "image/jpeg", Instant.now())));

    var response = controller.get(jwt(1L), 5L);

    assertEquals(200, response.getStatusCode().value());
    assertArrayEquals(new byte[] {7}, response.getBody());
  }

  @Test
  void 상대방_사진이_없으면_204다() {
    when(chatService.findCounterpartyId(5L, 1L)).thenReturn(2L);
    when(images.find(2L)).thenReturn(Optional.empty());

    assertEquals(204, controller.get(jwt(1L), 5L).getStatusCode().value());
  }

  @Test
  void 방_참여자가_아니면_사진을_조회하지_않는다() {
    when(chatService.findCounterpartyId(5L, 9L))
        .thenThrow(new CommonException(ErrorCode.ACCESS_DENIED));

    CommonException exception =
        assertThrows(CommonException.class, () -> controller.get(jwt(9L), 5L));

    assertEquals(ErrorCode.ACCESS_DENIED, exception.errorCode());
    verifyNoInteractions(images);
  }
}
