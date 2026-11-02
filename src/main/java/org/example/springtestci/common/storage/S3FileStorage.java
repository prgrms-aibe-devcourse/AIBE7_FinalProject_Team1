package org.example.springtestci.common.storage;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Private object storage; authorization and document signing remain in the application services.
 */
public class S3FileStorage implements FileStorage {
  private static final Set<String> EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "pdf");
  private final S3Client client;
  private final S3StorageProperties properties;

  public S3FileStorage(S3Client client, S3StorageProperties properties) {
    this.client = client;
    this.properties = properties;
  }

  @Override
  public String store(String originalFilename, InputStream content) throws IOException {
    int dot = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
    if (dot < 0) throw new IllegalArgumentException("Unsupported storage extension");
    return store("uploads", originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT), content);
  }

  @Override
  public String store(String directory, String extension, InputStream content) throws IOException {
    if (directory == null || !directory.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*")) {
      throw new IllegalArgumentException("Invalid storage directory");
    }
    if (extension == null || !EXTENSIONS.contains(extension)) {
      throw new IllegalArgumentException("Unsupported storage extension");
    }
    String key = properties.prefix() + "/" + directory + "/" + UUID.randomUUID() + "." + extension;
    Path temporary = Files.createTempFile("pastport-upload-", ".tmp");
    try {
      Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
      client.putObject(
          PutObjectRequest.builder().bucket(properties.bucket()).key(key).build(),
          RequestBody.fromFile(temporary));
      return key;
    } catch (SdkException exception) {
      throw new IOException("Storage upload failed");
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  @Override
  public Resource load(String key) throws IOException {
    validateKey(key);
    final long length;
    try {
      length =
          client
              .headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build())
              .contentLength();
    } catch (SdkException exception) {
      throw readFailure(exception);
    }
    return new AbstractResource() {
      @Override
      public String getDescription() {
        return "Private application object";
      }

      @Override
      public boolean exists() {
        return true;
      }

      @Override
      public boolean isReadable() {
        return true;
      }

      @Override
      public long contentLength() {
        return length;
      }

      @Override
      public InputStream getInputStream() throws IOException {
        try {
          return client.getObject(
              GetObjectRequest.builder().bucket(properties.bucket()).key(key).build());
        } catch (SdkException exception) {
          throw readFailure(exception);
        }
      }
    };
  }

  @Override
  public void delete(String key) throws IOException {
    validateKey(key);
    try {
      client.deleteObject(
          DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    } catch (S3Exception exception) {
      if (exception.statusCode() != 404) throw new IOException("Storage deletion failed");
    } catch (SdkException exception) {
      throw new IOException("Storage deletion failed");
    }
  }

  @Override
  public String createTemporaryUrl(String key, Instant expiresAt) {
    throw new UnsupportedOperationException(
        "Use authenticated product-image or document endpoints");
  }

  private void validateKey(String key) {
    String start = properties.prefix() + "/";
    if (key == null
        || !key.startsWith(start)
        || !key.substring(start.length())
            .matches(
                "[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg|jpeg|webp|pdf)")) {
      throw new IllegalArgumentException("Invalid storage key");
    }
  }

  private IOException readFailure(SdkException exception) {
    return exception instanceof S3Exception s3 && s3.statusCode() == 404
        ? new FileNotFoundException("Storage object not found")
        : new IOException("Storage read failed");
  }
}
