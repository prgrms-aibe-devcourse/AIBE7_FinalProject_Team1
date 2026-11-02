package org.example.springtestci.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.storage.s3")
public record S3StorageProperties(String bucket, String region, String prefix) {
  public S3StorageProperties {
    if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
      throw new IllegalArgumentException("Storage bucket must be configured");
    }
    if (region == null || !region.matches("[a-z0-9-]+")) {
      throw new IllegalArgumentException("Storage region must be configured");
    }
    if (prefix == null || !prefix.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*")) {
      throw new IllegalArgumentException("Invalid storage prefix");
    }
  }
}
