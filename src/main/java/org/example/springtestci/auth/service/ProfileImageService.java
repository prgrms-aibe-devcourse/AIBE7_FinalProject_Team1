package org.example.springtestci.auth.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.example.springtestci.auth.dto.ProfileImageContent;
import org.example.springtestci.auth.entity.UserProfileImage;
import org.example.springtestci.auth.repository.UserProfileImageRepository;
import org.example.springtestci.auth.repository.UserRepository;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 프로필 사진을 DB에 보관한다. 올라온 파일은 그대로 믿지 않고 서버에서 직접 디코딩해 가운데 정사각형을 256px JPEG로 다시 만들어 저장한다. 그래서 EXIF
 * 같은 부가 정보와 이상한 형식이 저장되지 않는다. 항상 호출한 회원 본인의 사진만 다룬다.
 */
@Service
public class ProfileImageService {

  static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
  static final int MAX_SOURCE_SIDE = 4096;
  static final int OUTPUT_SIDE = 256;
  static final String OUTPUT_TYPE = "image/jpeg";
  private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "png");

  private final UserProfileImageRepository imageRepository;
  private final UserRepository userRepository;
  private final Clock clock;

  /** 사진 저장소, 회원 저장소와 시각 공급자를 주입받는다. */
  public ProfileImageService(
      UserProfileImageRepository imageRepository, UserRepository userRepository, Clock clock) {
    this.imageRepository = imageRepository;
    this.userRepository = userRepository;
    this.clock = clock;
  }

  /** 회원의 사진을 조회한다. 없으면 비어 있는 값을 돌려준다. */
  @Transactional(readOnly = true)
  public Optional<ProfileImageContent> find(long userId) {
    return imageRepository
        .findById(userId)
        .map(
            image ->
                new ProfileImageContent(
                    image.getContent(), image.getContentType(), image.getUpdatedAt()));
  }

  /** 회원의 사진을 올린 파일로 바꾼다. 올바른 JPG·PNG가 아니거나 너무 크면 {@code PROFILE_IMAGE_INVALID}로 거부한다. */
  @Transactional
  public void replace(long userId, byte[] upload) {
    if (!userRepository.existsById(userId)) {
      throw new CommonException(ErrorCode.UNAUTHORIZED);
    }
    byte[] normalized = normalize(upload);
    var now = clock.instant();
    imageRepository
        .findById(userId)
        .ifPresentOrElse(
            image -> image.replace(normalized, OUTPUT_TYPE, now),
            () -> imageRepository.save(new UserProfileImage(userId, normalized, OUTPUT_TYPE, now)));
  }

  /** 회원의 사진을 지운다. 이미 없어도 오류 없이 끝난다. */
  @Transactional
  public void delete(long userId) {
    imageRepository.findById(userId).ifPresent(imageRepository::delete);
  }

  /** 디코딩 전에 크기부터 확인해 아주 큰 이미지로 메모리를 쓰지 못하게 하고, 가운데 정사각형을 256px JPEG로 만든다. */
  static byte[] normalize(byte[] upload) {
    if (upload == null || upload.length == 0 || upload.length > MAX_UPLOAD_BYTES) {
      throw new CommonException(ErrorCode.PROFILE_IMAGE_INVALID);
    }
    try (ImageInputStream input =
        ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
      Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
      if (readers == null || !readers.hasNext()) {
        throw new CommonException(ErrorCode.PROFILE_IMAGE_INVALID);
      }
      ImageReader reader = readers.next();
      try {
        if (!ALLOWED_FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
          throw new CommonException(ErrorCode.PROFILE_IMAGE_INVALID);
        }
        reader.setInput(input, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        if (width <= 0 || height <= 0 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE) {
          throw new CommonException(ErrorCode.PROFILE_IMAGE_INVALID);
        }
        return squareJpeg(reader.read(0));
      } finally {
        reader.dispose();
      }
    } catch (CommonException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new CommonException(ErrorCode.PROFILE_IMAGE_INVALID);
    }
  }

  private static byte[] squareJpeg(BufferedImage source) throws IOException {
    int side = Math.min(source.getWidth(), source.getHeight());
    int x = (source.getWidth() - side) / 2;
    int y = (source.getHeight() - side) / 2;
    BufferedImage output = new BufferedImage(OUTPUT_SIDE, OUTPUT_SIDE, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = output.createGraphics();
    try {
      // 투명한 PNG는 흰 바탕에 올려 JPEG로 저장한다.
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, OUTPUT_SIDE, OUTPUT_SIDE);
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.drawImage(source, 0, 0, OUTPUT_SIDE, OUTPUT_SIDE, x, y, x + side, y + side, null);
    } finally {
      graphics.dispose();
    }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    if (!ImageIO.write(output, "jpg", bytes)) {
      throw new IOException("jpeg writer unavailable");
    }
    return bytes.toByteArray();
  }
}
