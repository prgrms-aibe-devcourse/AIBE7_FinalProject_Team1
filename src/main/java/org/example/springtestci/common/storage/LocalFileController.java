package org.example.springtestci.common.storage;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 로컬·테스트의 제품 사진만 서명 URL로 제공한다. 문서 사본은 인증된 문서 다운로드 API에서 제공한다. */
@RestController
@Profile({"local", "test"})
@ConditionalOnProperty(
    prefix = "app.storage",
    name = "type",
    havingValue = "local",
    matchIfMissing = true)
@RequiredArgsConstructor
public class LocalFileController {

  private final LocalFileStorage storage;

  @GetMapping(LocalFileStorage.TEMPORARY_URL_PATH)
  ResponseEntity<Resource> download(
      Authentication authentication,
      @RequestParam String key,
      @RequestParam long expires,
      @RequestParam String sig)
      throws IOException {
    if (!storage.isPublicImageKey(key) || !storage.isValidTemporaryUrl(key, expires, sig)) {
      return ResponseEntity.status(403).build();
    }
    Resource resource = storage.load(key);
    if (!resource.exists()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .contentType(mediaTypeOf(key))
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store")
        .body(resource);
  }

  private MediaType mediaTypeOf(String key) {
    String lower = key.toLowerCase();
    if (lower.endsWith(".pdf")) {
      return MediaType.APPLICATION_PDF;
    }
    if (lower.endsWith(".png")) {
      return MediaType.IMAGE_PNG;
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return MediaType.IMAGE_JPEG;
    }
    return MediaType.APPLICATION_OCTET_STREAM;
  }
}
