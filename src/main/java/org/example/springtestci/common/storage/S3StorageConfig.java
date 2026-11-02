package org.example.springtestci.common.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "s3")
@EnableConfigurationProperties(S3StorageProperties.class)
public class S3StorageConfig {
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(S3Client.class)
  S3Client s3Client(S3StorageProperties properties) {
    return S3Client.builder()
        .region(Region.of(properties.region()))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }

  @Bean
  FileStorage s3FileStorage(S3Client client, S3StorageProperties properties) {
    return new S3FileStorage(client, properties);
  }
}
