package org.example.springtestci.common.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "test"})
@ConditionalOnProperty(
    prefix = "app.storage",
    name = "type",
    havingValue = "local",
    matchIfMissing = true)
public class LocalFileStorage implements FileStorage {

  public static final String TEMPORARY_URL_PATH = "/local-files";

  private final Path root;
  private final byte[] signingKey;
  private final Clock clock;

  public LocalFileStorage(
      @Value("${app.storage.local-root}") String root,
      @Value("${app.storage.signing-secret}") String signingSecret,
      Clock clock) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.signingKey = signingSecret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
  }

  @Override
  public String store(String originalFilename, InputStream content) throws IOException {
    Files.createDirectories(root);
    String key = UUID.randomUUID() + "-" + safeFilename(originalFilename);
    copy(key, content);
    return key;
  }

  @Override
  public String store(String directory, String extension, InputStream content) throws IOException {
    String key = directory + "/" + UUID.randomUUID() + "." + extension;
    Files.createDirectories(resolve(key).getParent());
    copy(key, content);
    return key;
  }

  @Override
  public Resource load(String key) throws IOException {
    return new UrlResource(resolve(key).toUri());
  }

  @Override
  public void delete(String key) throws IOException {
    Files.deleteIfExists(resolve(key));
  }

  @Override
  public String createTemporaryUrl(String key, Instant expiresAt) {
    long expires = expiresAt.getEpochSecond();
    return TEMPORARY_URL_PATH
        + "?key="
        + URLEncoder.encode(key, StandardCharsets.UTF_8)
        + "&expires="
        + expires
        + "&sig="
        + sign(key, expires);
  }

  /** 임시 URL의 서명과 만료 시각을 확인한다. */
  public boolean isValidTemporaryUrl(String key, long expires, String signature) {
    if (signature == null || Instant.now(clock).getEpochSecond() > expires) {
      return false;
    }
    return MessageDigest.isEqual(
        sign(key, expires).getBytes(StandardCharsets.UTF_8),
        signature.getBytes(StandardCharsets.UTF_8));
  }

  private String sign(String key, long expires) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
      return HexFormat.of()
          .formatHex(mac.doFinal((key + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("Failed to sign temporary url", exception);
    }
  }

  public boolean isPublicImageKey(String key) {
    if (key == null || !key.matches("product-images/[0-9]+/[A-Za-z0-9-]+\\.(png|jpg|jpeg|webp)"))
      return false;
    Path relative = Path.of(key).normalize();
    return relative.toString().replace('\\', '/').equals(key)
        && key.startsWith("product-images/")
        && (key.endsWith(".png")
            || key.endsWith(".jpg")
            || key.endsWith(".jpeg")
            || key.endsWith(".webp"));
  }

  private void copy(String key, InputStream content) throws IOException {
    Path destination = resolve(key);
    try {
      Files.copy(content, destination, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      try {
        Files.deleteIfExists(destination);
      } catch (IOException cleanupException) {
        exception.addSuppressed(cleanupException);
      }
      throw exception;
    }
  }

  private Path resolve(String key) {
    Path resolved = root.resolve(key).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("Invalid storage key");
    }
    return resolved;
  }

  private String safeFilename(String originalFilename) {
    String filename =
        originalFilename == null ? "file" : Path.of(originalFilename).getFileName().toString();
    String sanitized = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    return sanitized.isBlank() ? "file" : sanitized;
  }
}
