package org.example.springtestci.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.example.springtestci.SpringTestCiApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 실제 모델 검사와 연결 격리 검사가 공유한다. Flyway와 업무 DataSource를 같은 새 H2 메모리 DB로 고정한다. */
final class AiQualityTestContext {
  private AiQualityTestContext() {}

  static ConfigurableApplicationContext start(Class<?> fixtures, String key, String model) {
    String url =
        "jdbc:h2:mem:ai_live_quality_"
            + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.addPrimarySources(List.of(fixtures));
    // 키는 실행 인자·로그·보고서에 넣지 않는다. .env import와 외부 DB·Flyway 설정은 사용하지 않는다.
    app.addInitializers(
        ctx ->
            ctx.getEnvironment()
                .getPropertySources()
                .addFirst(
                    new MapPropertySource(
                        "live-quality-only",
                        Map.of(
                            "app.ai.listing.api-key",
                            key,
                            "app.ai.listing.model",
                            model,
                            "app.ai.listing.enabled",
                            true))));
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    return app.run(
        "--spring.profiles.active=test",
        "--spring.config.import=optional:classpath:ai-live-quality-no-external.properties",
        "--server.address=127.0.0.1",
        "--server.port=0",
        "--spring.datasource.url=" + url,
        "--spring.datasource.driver-class-name=org.h2.Driver",
        "--spring.datasource.username=sa",
        "--spring.datasource.password=",
        "--spring.datasource.hikari.jdbc-url=" + url,
        "--spring.datasource.hikari.username=sa",
        "--spring.datasource.hikari.password=",
        "--spring.flyway.url=" + url,
        "--spring.flyway.user=sa",
        "--spring.flyway.password=",
        "--spring.flyway.enabled=true",
        "--spring.flyway.locations=classpath:db/migration",
        "--spring.flyway.schemas=public",
        "--spring.flyway.default-schema=public",
        "--spring.jpa.hibernate.ddl-auto=validate",
        "--spring.jpa.properties.hibernate.default_schema=public",
        "--app.features.listing-history.enabled=true",
        "--app.ai.summary.enabled=false",
        "--app.ai.listing.timeout-seconds=20",
        "--app.jwt.secret=live-quality-fixture-only-signing-key-at-least-32-bytes");
  }
}
