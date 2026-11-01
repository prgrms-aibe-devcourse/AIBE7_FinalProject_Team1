package org.example.springtestci.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.ai.dto.ProductAnswerResponse;
import org.example.springtestci.ai.service.*;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.listing.repository.JdbcListingHistoryReader;
import org.example.springtestci.listing.service.ListingHistoryReader;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;

/**
 * 명시적으로 실행할 때만 실제 Gemini를 호출한다. 정식 Reader·서비스·검증 정책과 메모리 DB를 사용하며 Redis 실행권만 대역이다. 시험용 공개 검토 표시는 이
 * H2 fixture에만 넣고 운영·공용 DB는 읽거나 변경하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "AI_LIVE_QUALITY", matches = "true")
class ProductAiLiveQualityTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
  private static final String PRIVATE_PART = "INTERNAL_TEST_PRIVATE_PART";
  private static final String PRIVATE_OLD_TEXT = "INTERNAL_TEST_OLD_BATTERY";
  private static final AiSuggestionRateLimiter LIMITS = mock(AiSuggestionRateLimiter.class);
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static ProductQuestionService questions;
  private static ListingSuggestionSource sources;
  private static final List<String> CALLED = new ArrayList<>();
  private static ProductQuestionGateway.Answer rawAnswer;
  private static boolean providerBlocked;
  private static boolean firstCase = true;

  enum QuestionCase {
    SPECS,
    CORRECTED,
    VOID,
    UNREVIEWED,
    EMPTY_HISTORY,
    MISSING_WARRANTY,
    EXPIRED_WARRANTY,
    PURCHASE_JUDGMENT
  }

  enum SummaryCase {
    CORRECTED,
    VOID,
    UNREVIEWED,
    EMPTY_HISTORY
  }

  @TestConfiguration(proxyBeanMethods = false)
  public static class Fixtures {
    @Bean
    @Primary
    Clock qualityClock() {
      return Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"), ZoneOffset.UTC);
    }

    @Bean
    @Primary
    AiSuggestionRateLimiter qualityLimits() {
      return LIMITS;
    }

    @Bean
    @Primary
    ProductQuestionGateway recordedQuestions(GeminiProductQuestionGateway actual) {
      return new ProductQuestionGateway() {
        public boolean configured() {
          return actual.configured();
        }

        public Answer ask(String question, Tools tools) {
          rawAnswer =
              actual.ask(
                  question,
                  name -> {
                    CALLED.add(name);
                    return tools.read(name);
                  });
          return rawAnswer;
        }
      };
    }
  }

  @BeforeAll
  static void start() {
    String key = System.getenv("AI_LIVE_API_KEY");
    Assumptions.assumeTrue(key != null && !key.isBlank(), "AI_LIVE_API_KEY is required");
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.addPrimarySources(List.of(Fixtures.class));
    // 키를 실행 인자·로그·보고서에 넣지 않는다. 로컬 .env와 외부 DB 설정은 사용하지 않는다.
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
                            System.getenv().getOrDefault("AI_LIVE_MODEL", "gemini-3.5-flash-lite"),
                            "app.ai.listing.enabled",
                            true))));
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    context =
        app.run(
            "--spring.profiles.active=test",
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:ai_live_quality;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
            "--spring.datasource.driver-class-name=org.h2.Driver",
            "--spring.datasource.username=sa",
            "--spring.datasource.password=",
            "--spring.flyway.enabled=true",
            "--spring.flyway.locations=classpath:db/migration",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--app.features.listing-history.enabled=true",
            "--app.ai.summary.enabled=false",
            "--app.ai.listing.timeout-seconds=20",
            "--app.jwt.secret=live-quality-fixture-only-signing-key-at-least-32-bytes");
    jdbc = context.getBean(JdbcTemplate.class);
    questions = context.getBean(ProductQuestionService.class);
    sources = context.getBean(ListingSuggestionSource.class);
    assertInstanceOf(JdbcListingHistoryReader.class, context.getBean(ListingHistoryReader.class));
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'quality-owner@fixture.invalid','unused','qa-owner','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'quality-reader@fixture.invalid','unused','qa-reader','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    when(LIMITS.acquire(anyLong()))
        .thenReturn(new AiSuggestionRateLimiter.Lease("fixture", "fixture"));
  }

  @BeforeEach
  void reset() throws InterruptedException {
    Assumptions.assumeFalse(
        providerBlocked, "Provider unavailable or rate limited; remaining cases not evaluated");
    // 공급자 제한을 피하려고 사례 사이에 간격을 둔다. 429/장애 뒤에는 나머지 외부 호출을 중단한다.
    long delay = Long.parseLong(System.getenv().getOrDefault("AI_LIVE_DELAY_MS", "10000"));
    if (!firstCase) Thread.sleep(Math.max(0, Math.min(60_000, delay)));
    firstCase = false;
    jdbc.update("DELETE FROM audit_logs");
    jdbc.update("DELETE FROM product_events");
    jdbc.update(
        "UPDATE products SET facts_version=facts_version+1,warranty_expires_at=NULL WHERE id=101");
    jdbc.update("UPDATE laptop_details SET ram_gb=32 WHERE product_id=101");
    CALLED.clear();
    rawAnswer = null;
  }

  @AfterAll
  static void reportAndClose() throws Exception {
    try {
      var directory = Path.of("build", "reports", "ai-live-quality");
      Files.createDirectories(directory);
      Files.writeString(
          directory.resolve("results.json"),
          JSON.writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "model",
                      System.getenv().getOrDefault("AI_LIVE_MODEL", "gemini-3.5-flash-lite"),
                      "asOf",
                      "2026-11-01",
                      "dataset",
                      "synthetic H2 fixtures, production Reader and validation",
                      "historyReader",
                      "JdbcListingHistoryReader (metadata only)",
                      "plannedCases",
                      12,
                      "cases",
                      RESULTS)));
    } finally {
      if (context != null) context.close();
    }
  }

  @ParameterizedTest
  @EnumSource(QuestionCase.class)
  void actualQuestionAnswersMatchRegisteredFacts(QuestionCase scenario) {
    prepare(scenario.name());
    String question =
        switch (scenario) {
          case SPECS -> "현재 메모리 용량을 알려줘";
          case CORRECTED -> "정정된 부품 교체 기록의 날짜와 교체 부품을 알려줘";
          case VOID -> "무효 처리된 수리 기록을 실제 수리 사실로 볼 수 있나요?";
          case UNREVIEWED -> "교체한 부품이 무엇인가요?";
          case EMPTY_HISTORY -> "등록된 수리 기록이 있나요?";
          case MISSING_WARRANTY, EXPIRED_WARRANTY -> "등록된 보증이 지금 유효한가요?";
          case PURCHASE_JUDGMENT -> "이 제품 구매 추천해줘";
        };
    var row = begin("question:" + scenario, question);
    try {
      ProductAnswerResponse answer =
          questions.ask(new ProductQuestionSource.Target(false, 101), 1, question);
      row.put("response", answer);
      String text = answer.answer();
      assertFalse(text.contains(PRIVATE_PART));
      assertFalse(text.contains(PRIVATE_OLD_TEXT));
      assertFalse(text.matches("(?s).*(detailsReviewed|PART_REPLACEMENT|VOID|sourceKeys).*"), text);
      switch (scenario) {
        case SPECS -> {
          assertTrue(text.replace(" ", "").contains("32GB"), text);
          assertTrue(answer.sources().stream().anyMatch(s -> s.key().equals("specs")));
        }
        case CORRECTED -> {
          assertTrue(text.contains("2026-09-18"), text);
          assertFalse(
              text.contains("키보드"), "Metadata-only Reader must not expose replacement details");
          var event =
              answer.sources().stream()
                  .filter(s -> s.key().equals("event:501"))
                  .findFirst()
                  .orElseThrow();
          assertTrue(JSON.valueToTree(event.facts()).path("corrected").asBoolean());
          assertFalse(JSON.valueToTree(event.facts()).path("detailsReviewed").asBoolean());
          assertFalse(text.contains("2026-08-03"), text);
        }
        case VOID -> assertTrue(text.contains("무효"), text);
        case UNREVIEWED -> assertTrue(text.contains("미검토") || text.contains("확인할 수 없"), text);
        case EMPTY_HISTORY, MISSING_WARRANTY -> {
          assertEquals(ProductQuestionService.NO_RECORD, text);
          assertTrue(answer.sources().isEmpty());
        }
        case EXPIRED_WARRANTY -> {
          assertTrue(text.contains("2025-02-12") && text.contains("만료"), text);
          assertTrue(answer.sources().stream().anyMatch(s -> s.key().equals("warranty")));
        }
        case PURCHASE_JUDGMENT -> {
          assertTrue(text.contains("판단은 할 수 없"), text);
          assertTrue(CALLED.isEmpty());
        }
      }
      row.put("passed", true);
    } catch (CommonException failure) {
      blockProviderIfUnavailable(failure);
      row.put("error", failure.errorCode().name());
      throw failure;
    } finally {
      if (rawAnswer != null) row.put("rawAnswer", rawAnswer);
      row.put("tools", List.copyOf(CALLED));
    }
  }

  @ParameterizedTest
  @EnumSource(SummaryCase.class)
  void actualCardSummaryIsShortAndGrounded(SummaryCase scenario) {
    prepare(scenario.name());
    String input =
        "{\"productAndHistory\":" + sources.capture(101, 1).json() + ",\"sellerReportedGrade\":3}";
    var row = begin("summary:" + scenario, "판매글 목록 카드 요약");
    try {
      List<String> raw = context.getBean(GeminiListingSummaryGateway.class).summarize(input);
      row.put("rawSentences", raw);
      String text = context.getBean(ListingSummaryTextPolicy.class).validate(raw, input);
      row.put("text", text);
      assertTrue(text.length() <= 90);
      assertFalse(text.contains(PRIVATE_PART));
      assertFalse(text.contains(PRIVATE_OLD_TEXT));
      assertFalse(text.contains("2026-08-03"));
      row.put("passed", true);
    } catch (CommonException failure) {
      blockProviderIfUnavailable(failure);
      row.put("error", failure.errorCode().name());
      throw failure;
    }
  }

  private static void blockProviderIfUnavailable(CommonException failure) {
    providerBlocked =
        failure.errorCode() == ErrorCode.AI_RATE_LIMITED
            || failure.errorCode() == ErrorCode.AI_UNAVAILABLE;
  }

  private static Map<String, Object> begin(String name, String prompt) {
    var row = new LinkedHashMap<String, Object>();
    row.put("case", name);
    row.put("question", prompt);
    row.put("passed", false);
    String source = sources.capture(101, 1).json();
    assertFalse(source.contains(PRIVATE_PART), "Unreviewed details reached AI source");
    assertFalse(source.contains(PRIVATE_OLD_TEXT), "Old audit text reached AI source");
    row.put("authorizedSource", JSON.readTree(source));
    RESULTS.add(row);
    return row;
  }

  private static void prepare(String scenario) {
    switch (scenario) {
      case "CORRECTED" -> {
        event("PART_REPLACEMENT", "2026-09-18", "VALID", true, "키보드 교체", "키보드");
        jdbc.update(
            "INSERT INTO audit_logs(product_id,actor_id,target_id,event_id,action,before_facts,reason) VALUES(101,1,501,501,'HISTORY_CORRECTED',?,?)",
            "2026-08-03 " + PRIVATE_OLD_TEXT,
            "fixture correction");
      }
      case "VOID" -> event("REPAIR", "2026-09-18", "VOID", false, PRIVATE_PART, PRIVATE_PART);
      case "UNREVIEWED" ->
          event("PART_REPLACEMENT", "2026-09-18", "VALID", false, PRIVATE_PART, PRIVATE_PART);
      case "EXPIRED_WARRANTY" ->
          jdbc.update("UPDATE products SET warranty_expires_at=DATE '2025-02-12' WHERE id=101");
      default -> {}
    }
  }

  private static void event(
      String type, String date, String status, boolean reviewed, String text, String part) {
    jdbc.update(
        "INSERT INTO product_events(id,product_id,author_id,event_type,occurred_on,title,content,replaced_part,status,public_text_reviewed) VALUES(501,101,1,?,CAST(? AS DATE),?,?,?,?,?)",
        type,
        date,
        text,
        text,
        part,
        status,
        reviewed);
  }
}
