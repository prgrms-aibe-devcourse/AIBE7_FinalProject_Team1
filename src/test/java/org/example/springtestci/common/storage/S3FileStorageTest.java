package org.example.springtestci.common.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

class S3FileStorageTest {
  private S3Client client;
  private S3FileStorage storage;
  private static final String KEY =
      "app-files/documents/1/01234567-0123-4123-8123-0123456789ab.pdf";

  @BeforeEach
  void setup() {
    client = mock(S3Client.class);
    storage =
        new S3FileStorage(
            client, new S3StorageProperties("private-test-bucket", "ap-northeast-2", "app-files"));
  }

  @Test
  void bothOverloadsUseUuidAndNeverOriginalFilenameOrPublicAcl() throws Exception {
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            invocation -> {
              PutObjectRequest request = invocation.getArgument(0);
              RequestBody body = invocation.getArgument(1);
              assertNull(request.acl());
              assertEquals("private-test-bucket", request.bucket());
              try (var input = body.contentStreamProvider().newStream()) {
                assertArrayEquals(new byte[] {1, 2}, input.readAllBytes());
              }
              return PutObjectResponse.builder().build();
            });
    String original = storage.store("private-person-email@example.invalid.PDF", input());
    String directory = storage.store("documents/1", "pdf", input());
    assertTrue(original.matches("app-files/uploads/[0-9a-f-]{36}\\.pdf"));
    assertTrue(directory.matches("app-files/documents/1/[0-9a-f-]{36}\\.pdf"));
    assertNotEquals(original, directory);
    assertFalse(original.contains("private-person"));
  }

  static Stream<String> invalidPaths() {
    return Stream.of("", "../other", "/absolute", "documents/../x", "documents\\x", "documents//1");
  }

  @ParameterizedTest
  @MethodSource("invalidPaths")
  void rejectsDirectoryTraversal(String path) {
    assertThrows(IllegalArgumentException.class, () -> storage.store(path, "pdf", input()));
    verifyNoInteractions(client);
  }

  @Test
  void rejectsInvalidKeysAndExtensionsBeforeSdkAccess() {
    for (String key :
        new String[] {
          "other/documents/a.pdf",
          "app-files/../secret",
          "app-files/documents/1/not-a-uuid.pdf",
          KEY + "/../x",
          "app-files\\documents\\x"
        }) {
      assertThrows(IllegalArgumentException.class, () -> storage.load(key));
      assertThrows(IllegalArgumentException.class, () -> storage.delete(key));
    }
    assertThrows(IllegalArgumentException.class, () -> storage.store("file.exe", input()));
    assertThrows(IllegalArgumentException.class, () -> storage.store("noextension", input()));
    verifyNoInteractions(client);
  }

  @Test
  void translatesPutFailureWithoutLeakingSdkMessageOrKeys() {
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(SdkClientException.create("sensitive signed URL and key"));
    IOException failure =
        assertThrows(IOException.class, () -> storage.store("documents/1", "pdf", input()));
    assertEquals("Storage upload failed", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void loadIsRepeatableStreamingAndMetadataDoesNotOpenContent() throws Exception {
    when(client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().contentLength(2L).build());
    when(client.getObject(any(GetObjectRequest.class)))
        .thenAnswer(
            invocation ->
                new ResponseInputStream<>(
                    GetObjectResponse.builder().contentLength(2L).build(), input()));
    var resource = storage.load(KEY);
    assertTrue(resource.exists());
    assertTrue(resource.isReadable());
    assertEquals(2, resource.contentLength());
    assertFalse(resource.getDescription().contains(KEY));
    verify(client, never()).getObject(any(GetObjectRequest.class));
    try (var first = resource.getInputStream();
        var second = resource.getInputStream()) {
      assertArrayEquals(new byte[] {1, 2}, first.readAllBytes());
      assertArrayEquals(new byte[] {1, 2}, second.readAllBytes());
    }
  }

  @Test
  void missingAndFailedMetadataReadsHaveSafeIOExceptionContract() {
    when(client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().statusCode(404).message(KEY).build());
    assertThrows(FileNotFoundException.class, () -> storage.load(KEY));
    when(client.headObject(any(HeadObjectRequest.class))).thenThrow(SdkClientException.create(KEY));
    var failure = assertThrows(IOException.class, () -> storage.load(KEY));
    assertEquals("Storage read failed", failure.getMessage());
    assertNull(failure.getCause());
  }

  @Test
  void getFailureAfterMetadataIsAlsoTranslated() throws Exception {
    when(client.headObject(any(HeadObjectRequest.class)))
        .thenReturn(HeadObjectResponse.builder().contentLength(2L).build());
    when(client.getObject(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
    assertThrows(FileNotFoundException.class, () -> storage.load(KEY).getInputStream());
  }

  @Test
  void deletionIsIdempotentAndOtherFailuresAreSanitized() throws Exception {
    storage.delete(KEY);
    storage.delete(KEY);
    when(client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
    storage.delete(KEY);
    when(client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(SdkClientException.create(KEY));
    assertEquals(
        "Storage deletion failed",
        assertThrows(IOException.class, () -> storage.delete(KEY)).getMessage());
  }

  @Test
  void noDirectObjectUrlsAreIssuedEvenForImages() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> storage.createTemporaryUrl(KEY, Instant.now().plusSeconds(30)));
    verifyNoInteractions(client);
  }

  private ByteArrayInputStream input() {
    return new ByteArrayInputStream(new byte[] {1, 2});
  }
}
