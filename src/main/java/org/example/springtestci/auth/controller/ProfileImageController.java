package org.example.springtestci.auth.controller;

import java.io.IOException;
import org.example.springtestci.auth.dto.ProfileImageContent;
import org.example.springtestci.auth.service.ProfileImageService;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.common.security.CurrentUserIdResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 로그인한 회원 본인의 프로필 사진을 조회·변경·삭제한다. 다른 회원의 사진은 이 API로 다룰 수 없다. */
@RestController
@RequestMapping("/api/v1/me/profile-image")
public class ProfileImageController {

  private final ProfileImageService service;
  private final CurrentUserIdResolver currentUserIdResolver;

  /** 사진 서비스와 로그인 회원 식별기를 주입받는다. */
  public ProfileImageController(
      ProfileImageService service, CurrentUserIdResolver currentUserIdResolver) {
    this.service = service;
    this.currentUserIdResolver = currentUserIdResolver;
  }

  /** 내 사진을 이미지로 돌려준다. 사진이 없으면 본문 없이 204로 응답한다. */
  @GetMapping
  public ResponseEntity<byte[]> get(@AuthenticationPrincipal Jwt jwt) {
    return service
        .find(currentUserIdResolver.resolve(jwt))
        .map(this::image)
        .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
  }

  /** 올린 파일(multipart {@code file})로 내 사진을 바꾼다. */
  @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<Void> replace(
      @AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
    long userId = currentUserIdResolver.resolve(jwt);
    try {
      service.replace(userId, file.getBytes());
    } catch (IOException exception) {
      throw new CommonException(ErrorCode.PROFILE_IMAGE_INVALID);
    }
    return ResponseEntity.noContent().build();
  }

  /** 내 사진을 지운다. */
  @DeleteMapping
  public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
    service.delete(currentUserIdResolver.resolve(jwt));
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<byte[]> image(ProfileImageContent image) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(image.contentType()))
        // 본인 사진이라 공유 캐시에 남기지 않고, 변경 직후 바로 새 사진을 보이게 매번 확인한다.
        .cacheControl(CacheControl.noCache().cachePrivate())
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .header("X-Content-Type-Options", "nosniff")
        .body(image.content());
  }
}
