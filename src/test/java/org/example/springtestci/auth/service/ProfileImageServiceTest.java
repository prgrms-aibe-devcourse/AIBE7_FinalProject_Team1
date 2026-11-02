package org.example.springtestci.auth.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.example.springtestci.auth.entity.UserProfileImage;
import org.example.springtestci.auth.repository.UserProfileImageRepository;
import org.example.springtestci.auth.repository.UserRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileImageServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");

  private final UserProfileImageRepository images = mock(UserProfileImageRepository.class);
  private final UserRepository users = mock(UserRepository.class);
  private ProfileImageService service;

  @BeforeEach
  void setUp() {
    service = new ProfileImageService(images, users, Clock.fixed(NOW, ZoneOffset.UTC));
    when(users.existsById(7L)).thenReturn(true);
  }

  private static byte[] image(String format, int width, int height, int type) throws Exception {
    BufferedImage source = new BufferedImage(width, height, type);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(source, format, out);
    return out.toByteArray();
  }

  @Test
  void 직사각형_PNG는_256px_정사각_JPEG로_바뀐다() throws Exception {
    byte[] result =
        ProfileImageService.normalize(image("png", 600, 300, BufferedImage.TYPE_INT_ARGB));

    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
    assertEquals(256, decoded.getWidth());
    assertEquals(256, decoded.getHeight());
    // JPEG 시그니처(FF D8 FF)로 시작해야 한다.
    assertEquals((byte) 0xFF, result[0]);
    assertEquals((byte) 0xD8, result[1]);
  }

  @Test
  void 이미지가_아니거나_비어_있으면_거부한다() {
    for (byte[] bad : new byte[][] {null, new byte[0], "not an image".getBytes()}) {
      CommonException exception =
          assertThrows(CommonException.class, () -> ProfileImageService.normalize(bad));
      assertEquals(ErrorCode.PROFILE_IMAGE_INVALID, exception.errorCode());
    }
  }

  @Test
  void 허용_용량을_넘으면_디코딩하지_않고_거부한다() {
    byte[] tooLarge = new byte[ProfileImageService.MAX_UPLOAD_BYTES + 1];

    CommonException exception =
        assertThrows(CommonException.class, () -> ProfileImageService.normalize(tooLarge));

    assertEquals(ErrorCode.PROFILE_IMAGE_INVALID, exception.errorCode());
  }

  @Test
  void JPG와_PNG_외_형식은_거부한다() throws Exception {
    byte[] gif = image("gif", 20, 20, BufferedImage.TYPE_INT_RGB);

    CommonException exception =
        assertThrows(CommonException.class, () -> ProfileImageService.normalize(gif));

    assertEquals(ErrorCode.PROFILE_IMAGE_INVALID, exception.errorCode());
  }

  @Test
  void 사진이_없으면_새로_저장한다() throws Exception {
    when(images.findById(7L)).thenReturn(Optional.empty());

    service.replace(7L, image("png", 40, 40, BufferedImage.TYPE_INT_RGB));

    verify(images).save(any(UserProfileImage.class));
  }

  @Test
  void 사진이_있으면_같은_행의_내용을_바꾼다() throws Exception {
    UserProfileImage existing =
        new UserProfileImage(
            7L, new byte[] {1}, "image/jpeg", Instant.parse("2026-01-01T00:00:00Z"));
    when(images.findById(7L)).thenReturn(Optional.of(existing));

    service.replace(7L, image("png", 40, 40, BufferedImage.TYPE_INT_RGB));

    verify(images, never()).save(any());
    assertEquals(NOW, existing.getUpdatedAt());
    assertTrue(existing.getContent().length > 1);
  }

  @Test
  void 없는_회원이면_저장하지_않는다() throws Exception {
    when(users.existsById(8L)).thenReturn(false);

    CommonException exception =
        assertThrows(
            CommonException.class,
            () -> service.replace(8L, image("png", 40, 40, BufferedImage.TYPE_INT_RGB)));

    assertEquals(ErrorCode.UNAUTHORIZED, exception.errorCode());
    verify(images, never()).save(any());
  }

  @Test
  void 조회는_본인_회원_id로만_한다() {
    UserProfileImage stored = new UserProfileImage(7L, new byte[] {9, 9}, "image/jpeg", NOW);
    when(images.findById(7L)).thenReturn(Optional.of(stored));
    when(images.findById(8L)).thenReturn(Optional.empty());

    assertArrayEquals(new byte[] {9, 9}, service.find(7L).orElseThrow().content());
    assertTrue(service.find(8L).isEmpty());
  }

  @Test
  void 삭제는_있으면_지우고_없어도_오류가_없다() {
    UserProfileImage stored = new UserProfileImage(7L, new byte[] {1}, "image/jpeg", NOW);
    when(images.findById(7L)).thenReturn(Optional.of(stored));
    when(images.findById(8L)).thenReturn(Optional.empty());

    service.delete(7L);
    service.delete(8L);

    verify(images).delete(stored);
  }
}
