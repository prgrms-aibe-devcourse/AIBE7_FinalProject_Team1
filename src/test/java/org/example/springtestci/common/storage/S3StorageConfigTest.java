package org.example.springtestci.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

class S3StorageConfigTest {
  private ApplicationContextRunner context(String... profiles) {
    return new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
        .withUserConfiguration(
            S3StorageConfig.class, LocalFileStorage.class, LocalFileController.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withPropertyValues(
            "app.storage.local-root=build/storage-config-test",
            "app.storage.signing-secret=synthetic-storage-test-secret-32-bytes",
            "app.storage.s3.bucket=private-test-bucket",
            "app.storage.s3.region=ap-northeast-2",
            "app.storage.s3.prefix=app-files");
  }

  @Test
  void prodSupabaseHasExactlyOneStorageWithoutAwsAccess() {
    var client = mock(S3Client.class);
    context("prod", "supabase")
        .withBean(S3Client.class, () -> client)
        .withPropertyValues("app.storage.type=s3")
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(FileStorage.class)
                  .hasSingleBean(S3Client.class)
                  .doesNotHaveBean(LocalFileStorage.class);
              assertThat(context.getBean(FileStorage.class)).isInstanceOf(S3FileStorage.class);
              verifyNoInteractions(client);
            });
  }

  @Test
  void localAndTestRemainLocalUnlessS3ExplicitlySelected() {
    for (String profile : new String[] {"local", "test"}) {
      context(profile)
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(FileStorage.class)
                      .hasSingleBean(LocalFileController.class)
                      .doesNotHaveBean(S3Client.class));
      context(profile)
          .withBean(S3Client.class, () -> mock(S3Client.class))
          .withPropertyValues("app.storage.type=s3")
          .run(
              context ->
                  assertThat(context)
                      .hasSingleBean(FileStorage.class)
                      .doesNotHaveBean(LocalFileStorage.class)
                      .doesNotHaveBean(LocalFileController.class));
    }
  }

  @Test
  void missingBucketFailsClosed() {
    context("prod", "supabase")
        .withBean(S3Client.class, () -> mock(S3Client.class))
        .withPropertyValues("app.storage.type=s3", "app.storage.s3.bucket=")
        .run(context -> assertThat(context).hasFailed());
  }
}
