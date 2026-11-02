package org.example.springtestci.common.config;

import java.util.Map;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** PostgreSQL의 비트랜잭션 마이그레이션이 Flyway 잠금 트랜잭션과 서로 기다리지 않게 한다. */
@Configuration(proxyBeanMethods = false)
public class FlywayConfig {

  @Bean
  FlywayConfigurationCustomizer postgresSessionAdvisoryLock() {
    return configuration ->
        configuration.configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
  }
}
