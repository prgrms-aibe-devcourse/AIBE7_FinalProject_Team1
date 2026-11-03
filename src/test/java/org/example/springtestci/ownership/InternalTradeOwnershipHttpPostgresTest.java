package org.example.springtestci.ownership;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.example.db.ConvergenceTool;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.ai.repository.ListingSummaryRepository;
import org.example.springtestci.ai.service.*;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.security.RequestRateLimiter;
import org.example.springtestci.history.repository.HistoryRepository;
import org.example.springtestci.ownership.repository.InternalTradeOwnershipRepository;
import org.example.springtestci.ownership.service.InternalTradeOwnershipService;
import org.example.springtestci.ownership.service.OwnershipTransferService;
import org.example.springtestci.product.service.SerialNumberProtector;
import org.example.springtestci.trade.repository.*;
import org.example.springtestci.trade.service.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 정식 P2/P3/P4·ownership·history·document·판매·AI 경계를 PostgreSQL과 Bearer 인증 HTTP로 검증한다. */
@EnabledIfEnvironmentVariable(
    named = "SALE_DRAFT_TEST_DB_URL",
    matches = "jdbc:postgresql:.*sale_ai_test.*")
class InternalTradeOwnershipHttpPostgresTest {
  private static final String SECRET = "internal-ownership-http-test-only-key-at-least-32-bytes";
  private static final String DRAFT = "/api/v1/products/101/listing-draft";
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AtomicReference<String> FAILURE = new AtomicReference<>();
  private static final AtomicReference<Runnable> QUESTION_GATE = new AtomicReference<>();
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate jdbc;
  private static DriverManagerDataSource schemaSource;
  private static String base, schema;
  private long requestId, otherId, draftId;

  @TestConfiguration(proxyBeanMethods = false)
  static class Faults {
    @Bean
    @Primary
    RequestRateLimiter requestLimits() {
      return mock(RequestRateLimiter.class);
    }

    @Bean
    @Primary
    AiSuggestionRateLimiter aiLimits() {
      var limits = mock(AiSuggestionRateLimiter.class);
      when(limits.acquire(anyLong())).thenReturn(new AiSuggestionRateLimiter.Lease("test", "test"));
      return limits;
    }

    @Bean
    @Primary
    ProductQuestionGateway questions() {
      return new ProductQuestionGateway() {
        public boolean configured() {
          return true;
        }

        public Answer ask(String question, Tools tools) {
          tools.read("get_product_specs");
          if (QUESTION_GATE.get() != null) QUESTION_GATE.get().run();
          return new Answer("등록된 메모리는 18GB입니다.", List.of("specs"));
        }
      };
    }

    @Bean
    @Primary
    InternalTradeOwnershipRepository faultRepository(JdbcTemplate jdbc) {
      return new InternalTradeOwnershipRepository(jdbc) {
        @Override
        public long insertPending(
            InternalTransferPendingWriter.Command c, String hash, Instant now) {
          long id = super.insertPending(c, hash, now);
          fail("pending");
          return id;
        }

        @Override
        public int completeTransfer(long id, Instant at) {
          int n = super.completeTransfer(id, at);
          fail("transfer");
          return n;
        }

        @Override
        public int transferOwner(InternalTradeCompletionPort.Command c) {
          int n = super.transferOwner(c);
          fail("owner");
          return n;
        }

        @Override
        public void protectDocuments(long product, Instant at) {
          super.protectDocuments(product, at);
          fail("documents");
        }
      };
    }

    @Bean
    @Primary
    HistoryRepository faultHistory(JdbcTemplate jdbc) {
      return new HistoryRepository(jdbc) {
        @Override
        public long insertTransferEvent(
            long product, long transfer, LocalDate date, OffsetDateTime at) {
          long id = super.insertTransferEvent(product, transfer, date, at);
          fail("history");
          return id;
        }

        @Override
        public void makeAllPrivate(long product) {
          super.makeAllPrivate(product);
          fail("history-publication");
        }
      };
    }

    @Bean
    @Primary
    TradeAuditRepository faultAudit(JdbcTemplate jdbc) {
      return new TradeAuditRepository(jdbc) {
        @Override
        public void log(
            long product,
            Long actor,
            long target,
            String action,
            String before,
            String after,
            String reason) {
          super.log(product, actor, target, action, before, after, reason);
          if ("OWNERSHIP_TRANSFERRED".equals(action)) fail("audit");
        }
      };
    }

    @Bean
    @Primary
    TradeAuditRecorder finalAudit(JdbcTemplate jdbc) {
      return new JdbcTradeAuditRecorder(jdbc, JSON) {
        @Override
        public void tradeCompleted(
            long product, long actor, long request, long version, Instant at) {
          super.tradeCompleted(product, actor, request, version, at);
          fail("trade-audit");
        }
      };
    }
  }

  /** 스키마 소유 연결을 컨텍스트와 별도로 보관해, 마이그레이션·기동 실패 때도 정리할 수 있다. */
  @BeforeAll
  static void start() throws Exception {
    String url = System.getenv("SALE_DRAFT_TEST_DB_URL");
    if (url == null || !url.startsWith("jdbc:postgresql:") || !url.contains("sale_ai_test"))
      throw new IllegalStateException("Dedicated test DB required");
    schema = "internal_ownership_" + Long.toUnsignedString(System.nanoTime());
    url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    schemaSource =
        new DriverManagerDataSource(
            url,
            System.getenv("SALE_DRAFT_TEST_DB_USER"),
            System.getenv("SALE_DRAFT_TEST_DB_PASSWORD"));
    boolean legacy = "legacy".equals(System.getenv("CONVERGENCE_API_LINEAGE"));
    if (legacy) {
      prepareLegacy(url);
    } else {
      // 정식 history를 먼저 만든 뒤 기존 pending P2/P3를 적용한다. 버전 번호와 원본 SQL은 수정하지 않는다.
      Flyway.configure()
          .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
          .dataSource(schemaSource)
          .schemas(schema)
          .defaultSchema(schema)
          .locations("classpath:db/migration")
          .load()
          .migrate();
      Flyway.configure()
          .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
          .dataSource(schemaSource)
          .schemas(schema)
          .defaultSchema(schema)
          .locations(
              "classpath:db/migration",
              "classpath:db/pending/trade-p2",
              "classpath:db/pending/trade-p3",
              "classpath:db/convergence",
              "classpath:db/deploy")
          .outOfOrder(true)
          .load()
          .migrate();
    }
    var app = new SpringApplication(SpringTestCiApplication.class);
    app.addPrimarySources(List.of(Faults.class));
    app.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "WARN"));
    context =
        app.run(
            "--spring.profiles.active=test",
            "--server.address=127.0.0.1",
            "--server.port=0",
            "--spring.datasource.url=" + url,
            "--spring.datasource.driver-class-name=org.postgresql.Driver",
            "--spring.datasource.username=" + System.getenv("SALE_DRAFT_TEST_DB_USER"),
            "--spring.datasource.password=" + System.getenv("SALE_DRAFT_TEST_DB_PASSWORD"),
            "--spring.flyway.enabled=true",
            "--spring.flyway.out-of-order=false",
            "--spring.flyway.schemas=" + schema,
            "--spring.flyway.default-schema=" + schema,
            "--spring.flyway.locations="
                + String.join(",", ConvergenceTool.locations(legacy ? "legacy" : "pending")),
            "--spring.flyway.ignore-migration-patterns=",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.properties.hibernate.default_schema=" + schema,
            "--app.features.listing-history.enabled=true",
            "--app.features.trade-purchase-requests.enabled=true",
            "--app.features.trade-p3.enabled=true",
            "--app.features.trade-p4.enabled=true",
            "--app.ai.summary.enabled=false",
            "--app.ai.listing.api-key=",
            "--app.jwt.secret=" + SECRET);
    jdbc = context.getBean(JdbcTemplate.class);
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,created_at,updated_at) VALUES(1,'seller@internal.invalid','unused','seller','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'buyer@internal.invalid','unused','buyer','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(3,'other@internal.invalid','unused','other','USER',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    new ResourceDatabasePopulator(new ClassPathResource("db/fixtures/listing-draft-products.sql"))
        .execute(context.getBean(javax.sql.DataSource.class));
    jdbc.execute(
        "CREATE TABLE draft_cleanup_guard(draft_id BIGINT PRIMARY KEY REFERENCES listing_drafts(id))");
    base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
  }

  /** 컨텍스트 생성 전 실패도 정리한다. 시험에서 생성한 고유 스키마만 제거한다. */
  @AfterAll
  static void stop() {
    try {
      if (schemaSource != null && schema != null) {
        new JdbcTemplate(schemaSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
      }
    } finally {
      if (context != null) context.close();
    }
  }

  /** 격리 스키마에 원본 기반 구이력을 합성하고 정규화·purge를 각각 커밋한 뒤 strict migration을 적용한다. */
  private static void prepareLegacy(String url) throws Exception {
    JdbcTemplate setup = new JdbcTemplate(schemaSource);
    setup.execute("create schema " + schema);
    var configuration =
        ConvergenceTool.configuration(
            url,
            System.getenv("SALE_DRAFT_TEST_DB_USER"),
            System.getenv("SALE_DRAFT_TEST_DB_PASSWORD"),
            schema,
            "legacy");
    configuration.target("2026103001").load().migrate();
    setup.execute(
        "update flyway_schema_history set version='2026102706',script='V2026102706__support_listing_summary_refresh.sql' where version='2026102701'");
    setup.execute(
        "update flyway_schema_history set version='2026102701',script='V2026102701__create_purchase_requests.sql' where version='2026102103'");
    setup.execute(
        "update flyway_schema_history set version='2026102702',script='V2026102702__create_ownership_tables.sql' where version='2026102803'");
    setup.execute(
        "update flyway_schema_history set version='2026102707',script='V2026102707__add_ownership_pending_slot.sql' where version='2026102804'");
    try (var connection = schemaSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.createStatement()) {
        statement.execute(
            "select set_config('pastport.expected_database',current_database(),true),set_config('pastport.expected_schema',current_schema(),true)");
      }
      ConvergenceTool.normalize(connection);
      connection.commit();
      ConvergenceTool.purge(connection);
      connection.commit();
    }
    Flyway finalFlyway =
        ConvergenceTool.configuration(
                url,
                System.getenv("SALE_DRAFT_TEST_DB_USER"),
                System.getenv("SALE_DRAFT_TEST_DB_PASSWORD"),
                schema,
                "legacy")
            .load();
    finalFlyway.migrate();
    finalFlyway.validate();
    assertEquals(0, finalFlyway.migrate().migrationsExecuted);
  }

  @BeforeEach
  void fixtures() throws Exception {
    FAILURE.set(null);
    QUESTION_GATE.set(null);
    jdbc.execute(
        "TRUNCATE draft_cleanup_guard,audit_logs,ownership_transfers,product_events,documents,listing_drafts,listings,chat_rooms RESTART IDENTITY CASCADE");
    jdbc.update("UPDATE products SET owner_id=1,facts_version=7,archived_at=NULL WHERE id=101");
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES(11,101,1,'internal sale',10000,4,'registered description','ACTIVE',CURRENT_TIMESTAMP,9001,0,1)");
    jdbc.update(
        "INSERT INTO chat_rooms(id,listing_id,seller_id,inquirer_id) VALUES(21,11,1,2),(22,11,1,3)");
    jdbc.update(
        "INSERT INTO product_events(id,product_id,author_id,event_type,occurred_on,title,content,status,visibility) VALUES(601,101,1,'REPAIR',DATE '2026-10-20','private repair','unreviewed content','VALID','PUBLIC')");
    jdbc.update(
        "INSERT INTO documents(id,product_id,uploader_id,object_key,content_type,byte_size,version,mask_confirmed_at) VALUES(501,101,1,'masked-test-copy.png','image/png',100,1,CURRENT_TIMESTAMP)");
    var draft =
        send(
            "POST",
            DRAFT,
            1,
            "{\"title\":\"seller private draft\",\"selectedImageIds\":[301,302]}");
    assertEquals(201, draft.statusCode(), draft.body());
    draftId = data(draft).path("draftId").asLong();
    requestId = createRequest(21, 2);
    otherId = createRequest(22, 3);
  }

  @Test
  void realReservationCreatesAnInternalPendingRecordWithoutTransferringOwnership()
      throws Exception {
    reserve();
    assertReserved(0);
    assertEquals("INTERNAL", text("SELECT transfer_type FROM ownership_transfers"));
    assertEquals(64, text("SELECT token_hash FROM ownership_transfers").length());
    assertTrue(
        jdbc.queryForObject(
            "SELECT expires_at> CURRENT_TIMESTAMP + INTERVAL '100 years' FROM ownership_transfers",
            Boolean.class));
    transaction()
        .executeWithoutResult(
            s ->
                context
                    .getBean(InternalTradeOwnershipService.class)
                    .createPending(
                        InternalTransferPendingWriter.Command.internalPending(
                            101, requestId, 1, 2)));
    assertEquals(1, count("ownership_transfers", "1=1"));
    assertEquals(1, count("audit_logs", "action='OWNERSHIP_TRANSFER_CREATED'"));
  }

  @Test
  void pendingWriteFailureRollsBackTheWholeReservationAndCanBeRetried() throws Exception {
    FAILURE.set("pending");
    assertTrue(accept().statusCode() >= 400);
    assertEquals("ACTIVE", listingStatus());
    assertEquals(
        "PENDING", text("SELECT request_status FROM purchase_requests WHERE id=" + requestId));
    assertEquals(0, count("ownership_transfers", "1=1"));
    assertEquals(0, count("audit_logs", "1=1"));
    assertEquals(1, owner());
    assertDraftPreserved();
    FAILURE.set(null);
    reserve();
  }

  @Test
  void externalLinksCannotAttachAnArbitraryInternalPurchaseRequest() throws Exception {
    var response =
        send(
            "POST",
            "/api/v1/ownership/transfers",
            1,
            "{\"productId\":101,\"toUserId\":2,\"purchaseRequestId\":" + requestId + "}");
    assertEquals(400, response.statusCode(), response.body());
    assertEquals(0, count("ownership_transfers", "1=1"));
    assertEquals("ACTIVE", listingStatus());
    assertDraftPreserved();
  }

  @Test
  void internalReservationsCannotBeAcceptedVerifiedOrCancelledThroughTheExternalLinkApi()
      throws Exception {
    reserve();
    long transfer = jdbc.queryForObject("SELECT id FROM ownership_transfers", Long.class);
    assertEquals(
        409,
        send("POST", "/api/v1/ownership/transfers/" + transfer + "/cancel", 1, null).statusCode());
    String raw = "internal-only-synthetic-token";
    jdbc.update(
        "UPDATE ownership_transfers SET token_hash=?", OwnershipTransferService.hashToken(raw));
    assertEquals(
        404, send("GET", "/api/v1/ownership/transfers/verify?token=" + raw, 2, null).statusCode());
    assertEquals(
        409,
        send(
                "POST",
                "/api/v1/ownership/transfers/accept",
                2,
                JSON.writeValueAsString(Map.of("token", raw)))
            .statusCode());
    assertReserved(0);
  }

  @Test
  void pendingExternalTransferBlocksNewUserHistoryUntilItIsCancelled() throws Exception {
    jdbc.update("UPDATE listings SET status='CANCELLED',active_slot=NULL WHERE id=11");
    var created =
        send("POST", "/api/v1/ownership/transfers", 1, "{\"productId\":101,\"toUserId\":2}");
    assertEquals(201, created.statusCode(), created.body());
    long transferId = data(created).path("transferId").asLong();

    var blocked =
        send(
            "POST",
            "/api/v1/products/101/events",
            1,
            "{\"eventType\":\"INSPECTION\",\"occurredOn\":\"2026-10-21\",\"title\":\"transfer pending inspection\"}");
    assertEquals(409, blocked.statusCode(), blocked.body());
    assertEquals("HISTORY_004", JSON.readTree(blocked.body()).path("code").asText());
    assertEquals(0, count("product_events", "title='transfer pending inspection'"));

    assertEquals(
        200,
        send("POST", "/api/v1/ownership/transfers/" + transferId + "/cancel", 1, null)
            .statusCode());
    assertEquals(
        201,
        send(
                "POST",
                "/api/v1/products/101/events",
                1,
                "{\"eventType\":\"INSPECTION\",\"occurredOn\":\"2026-10-21\",\"title\":\"transfer cancelled inspection\"}")
            .statusCode());
  }

  @Test
  void bothPartiesCompleteAndPublicAccessEndsWhileTheNewOwnerKeepsHistoryAndDocuments()
      throws Exception {
    reserve();
    var waiting = complete(1, 7);
    assertEquals(200, waiting.statusCode(), waiting.body());
    assertFalse(data(waiting).path("completed").asBoolean());
    assertReserved(1);
    var done = complete(2, 7);
    assertEquals(200, done.statusCode(), done.body());
    assertTrue(data(done).path("completed").asBoolean());
    assertCompleted();
    for (long user : List.of(1L, 2L, 3L)) {
      assertEquals(404, send("GET", "/api/v1/listings/11", user, null).statusCode());
      assertEquals(
          404,
          send("POST", "/api/v1/listings/11/ai/questions", user, "{\"question\":\"현재 메모리는?\"}")
              .statusCode());
      assertEquals(404, send("GET", "/api/v1/listings/11/ai-summary", user, null).statusCode());
    }
    assertContainsId(send("GET", "/api/v1/products/101/events", 2, null), "eventId", 601);
    assertEquals(404, send("GET", "/api/v1/products/101/events", 1, null).statusCode());
    assertContainsId(send("GET", "/api/v1/products/101/documents", 2, null), "id", 501);
    assertTrue(send("GET", "/api/v1/products/101/documents", 1, null).statusCode() >= 400);
    assertTrue(send("DELETE", "/api/v1/documents/501", 2, null).statusCode() >= 400);
    assertEquals(
        200,
        send("POST", "/api/v1/products/101/ai/questions", 2, "{\"question\":\"현재 메모리는?\"}")
            .statusCode());
    assertEquals(
        404,
        send("POST", "/api/v1/products/101/ai/questions", 1, "{\"question\":\"현재 메모리는?\"}")
            .statusCode());
    jdbc.update(
        "UPDATE products SET serial_number_ciphertext=? WHERE id=101",
        context.getBean(SerialNumberProtector.class).encrypt("HANDOVER-SERIAL-101"));
    var updated =
        send(
            "PATCH",
            "/api/v1/products/101",
            2,
            "{\"title\":\"buyer owned laptop\",\"purchaseDate\":\"2026-10-01\",\"warrantyExpiresAt\":\"2027-10-01\",\"reason\":\"ownership handover check\"}");
    assertEquals(200, updated.statusCode(), updated.body());
    assertEquals("buyer owned laptop", data(updated).path("title").asText());
    assertEquals(
        2L,
        jdbc.queryForObject(
            "SELECT author_id FROM product_field_authors WHERE product_id=101 AND field_name='TITLE'",
            Long.class));
    assertEquals(
        201,
        send("POST", DRAFT, 2, "{\"title\":\"buyer fresh draft\",\"selectedImageIds\":[301]}")
            .statusCode());
  }

  @Test
  void buyerFirstAndSellerLastAlsoCompletes() throws Exception {
    reserve();
    assertEquals(200, complete(2, 7).statusCode());
    assertEquals(1, owner());
    assertEquals(200, complete(1, 7).statusCode());
    assertCompleted();
  }

  @Test
  void unconfirmedStaleAndUnrelatedRequestsCannotFinishTheTransfer() throws Exception {
    reserve();
    assertEquals(404, complete(3, 7).statusCode());
    assertEquals(409, complete(1, 6).statusCode());
    assertEquals(400, complete(1, 0).statusCode());
    assertReserved(0);
    var contextResponse = send("GET", path("completion-context"), 2, null);
    assertEquals(200, contextResponse.statusCode(), contextResponse.body());
    assertEquals(7, data(contextResponse).path("factsVersion").asLong());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "wrong-buyer", "wrong-type", "cancelled"})
  void absentOrMismatchedInternalPreparationRollsBackTheLastConfirmation(String kind)
      throws Exception {
    reserve();
    assertEquals(200, complete(1, 7).statusCode());
    switch (kind) {
      case "missing" -> jdbc.update("DELETE FROM ownership_transfers");
      case "wrong-buyer" -> jdbc.update("UPDATE ownership_transfers SET to_user_id=3");
      case "wrong-type" -> jdbc.update("UPDATE ownership_transfers SET transfer_type='DIRECT'");
      case "cancelled" ->
          jdbc.update(
              "UPDATE ownership_transfers SET status='CANCELLED',pending_slot=NULL,cancelled_at=CURRENT_TIMESTAMP");
    }
    assertEquals(409, complete(2, 7).statusCode());
    assertEquals("RESERVED", listingStatus());
    assertEquals(1, owner());
    assertEquals(7, version());
    assertDraftPreserved();
    assertEquals(0, count("product_events", "event_type='TRANSFER'"));
    assertEquals(
        0,
        count("purchase_requests", "id=" + requestId + " AND buyer_confirmed_version IS NOT NULL"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "transfer",
        "owner",
        "history",
        "history-publication",
        "documents",
        "audit",
        "trade-audit"
      })
  void everyCompletionWriteFailureRollsBackAllDomainsAndAllowsRetry(String phase) throws Exception {
    reserve();
    assertEquals(200, complete(1, 7).statusCode());
    FAILURE.set(phase);
    var response = complete(2, 7);
    assertTrue(response.statusCode() >= 400, response.body());
    assertReserved(1);
    FAILURE.set(null);
    assertEquals(200, complete(2, 7).statusCode());
    assertCompleted();
  }

  @Test
  void requiredDraftCleanupFailureRollsBackCompletion() throws Exception {
    reserve();
    assertEquals(200, complete(1, 7).statusCode());
    jdbc.update("INSERT INTO draft_cleanup_guard(draft_id) VALUES(?)", draftId);
    assertTrue(complete(2, 7).statusCode() >= 400);
    assertReserved(1);
    jdbc.update("DELETE FROM draft_cleanup_guard");
    assertEquals(200, complete(2, 7).statusCode());
    assertCompleted();
  }

  @Test
  void historyChangeRequiresReviewAndBothPartiesMustConfirmTheNewVersion() throws Exception {
    reserve();
    assertEquals(200, complete(1, 7).statusCode());
    var created =
        send(
            "POST",
            "/api/v1/products/101/events",
            1,
            "{\"eventType\":\"INSPECTION\",\"occurredOn\":\"2026-10-21\",\"title\":\"new inspection\"}");
    assertEquals(201, created.statusCode(), created.body());
    long event = data(created).path("eventId").asLong();
    assertEquals(8, version());
    assertEquals(409, complete(2, 7).statusCode());
    assertEquals(409, complete(2, 8).statusCode());
    assertEquals(
        200,
        send("POST", path("change-reviews/" + event), 2, "{\"decision\":\"KEEP\"}").statusCode());
    assertEquals(200, complete(2, 8).statusCode());
    assertEquals(1, owner());
    assertEquals(200, complete(1, 8).statusCode());
    assertEquals(2, owner());
    assertEquals(9, version());
    assertEquals("SOLD", listingStatus());
  }

  @Test
  void repeatedCompletionDoesNotRepeatOwnershipHistoryAuditOrDraftDeletion() throws Exception {
    reserve();
    assertEquals(200, complete(1, 7).statusCode());
    assertEquals(200, complete(2, 7).statusCode());
    assertEquals(201, send("POST", DRAFT, 2, "{\"title\":\"keep buyer draft\"}").statusCode());
    assertEquals(200, complete(1, 7).statusCode());
    assertEquals(200, complete(2, 7).statusCode());
    assertEquals(1, count("product_events", "event_type='TRANSFER'"));
    assertEquals(8, version());
    assertEquals(1, count("audit_logs", "action='OWNERSHIP_TRANSFERRED'"));
    assertEquals("keep buyer draft", data(send("GET", DRAFT, 2, null)).path("title").asString());
    jdbc.update(
        "UPDATE products SET owner_id=3,facts_version=9,archived_at=CURRENT_TIMESTAMP WHERE id=101");
    assertEquals(200, complete(1, 7).statusCode());
    assertEquals(200, complete(2, 7).statusCode());
    assertEquals(3, owner());
    assertEquals(9, version());
  }

  @Test
  void simultaneousConfirmationsCommitOneTransferAndOneSystemEvent() throws Exception {
    reserve();
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var seller =
          pool.submit(
              () -> {
                start.await();
                return complete(1, 7);
              });
      var buyer =
          pool.submit(
              () -> {
                start.await();
                return complete(2, 7);
              });
      start.countDown();
      var a = seller.get(20, TimeUnit.SECONDS);
      var b = buyer.get(20, TimeUnit.SECONDS);
      assertEquals(200, a.statusCode(), a.body());
      assertEquals(200, b.statusCode(), b.body());
      assertNotEquals(data(a).path("completed").asBoolean(), data(b).path("completed").asBoolean());
      assertCompleted();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cancellationAndCompletionHaveOneAtomicWinner() throws Exception {
    reserve();
    assertEquals(200, complete(1, 7).statusCode());
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var cancel =
          pool.submit(
              () -> {
                start.await();
                return send("POST", path("cancel"), 2, "{\"reason\":\"SELF_CIRCUMSTANCES\"}");
              });
      var done =
          pool.submit(
              () -> {
                start.await();
                return complete(2, 7);
              });
      start.countDown();
      var a = cancel.get(20, TimeUnit.SECONDS);
      var b = done.get(20, TimeUnit.SECONDS);
      assertEquals(Set.of(200, 409), Set.of(a.statusCode(), b.statusCode()));
      if (b.statusCode() == 200) assertCompleted();
      else {
        assertEquals("ACTIVE", listingStatus());
        assertEquals(1, owner());
        assertEquals(7, version());
        assertDraftPreserved();
        assertEquals("CANCELLED", text("SELECT status FROM ownership_transfers"));
        assertEquals(0, count("product_events", "event_type='TRANSFER'"));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void lateAiSummaryAndQuestionCannotPublishResultsAfterTheRealTransfer() throws Exception {
    reserve();
    var pending = new ListingSummaryRepository.Pending(11, UUID.randomUUID());
    Instant queuedAt = Instant.now();
    context
        .getBean(ListingSummaryRepository.class)
        .queue(
            11,
            7,
            jdbc.queryForObject("SELECT content_version FROM listings WHERE id=11", Long.class),
            pending.attemptId(),
            queuedAt.minusSeconds(1),
            false,
            queuedAt.minusSeconds(1));
    var job = context.getBean(ListingSummaryStore.class).claim(pending);
    assertNotNull(job);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    QUESTION_GATE.set(
        () -> {
          entered.countDown();
          try {
            if (!release.await(15, TimeUnit.SECONDS))
              throw new IllegalStateException("Question test gate timed out");
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
          }
        });
    var question =
        async("POST", "/api/v1/listings/11/ai/questions", 2, "{\"question\":\"현재 메모리는?\"}");
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      assertEquals(200, complete(1, 7).statusCode());
      assertEquals(200, complete(2, 7).statusCode());
      release.countDown();
      assertEquals(404, question.get(20, TimeUnit.SECONDS).statusCode());
      assertThrows(
          CommonException.class,
          () -> context.getBean(ListingSummaryStore.class).complete(job, "late result"));
      context.getBean(ListingSummaryStore.class).fail(pending, "AI_005");
      assertNull(
          jdbc.queryForObject("SELECT summary_text FROM listings WHERE id=11", String.class));
    } finally {
      release.countDown();
      QUESTION_GATE.set(null);
    }
  }

  @Test
  void internalPortsRequireTheCallerTransactionAndCannotBypassTheTradeState() throws Exception {
    var service = context.getBean(InternalTradeOwnershipService.class);
    var pending = InternalTransferPendingWriter.Command.internalPending(101, requestId, 1, 2);
    assertThrows(IllegalTransactionStateException.class, () -> service.createPending(pending));
    reserve();
    var completed =
        new InternalTradeCompletionPort.Command(101, requestId, 11, 1, 2, 2, 7, Instant.now());
    assertThrows(IllegalTransactionStateException.class, () -> service.complete(completed));
    assertThrows(
        CommonException.class,
        () -> transaction().executeWithoutResult(s -> service.complete(completed)));
    assertReserved(0);
  }

  private void assertReserved(int confirmations) {
    assertEquals(1, count("product_events", "id=601 AND visibility='PUBLIC'"));
    assertEquals("RESERVED", listingStatus());
    assertEquals(1, owner());
    assertEquals(7, version());
    assertEquals("PENDING", text("SELECT status FROM ownership_transfers"));
    assertEquals(0, count("product_events", "event_type='TRANSFER'"));
    assertEquals(0, count("documents", "protected_at IS NOT NULL"));
    assertEquals(
        confirmations,
        count("purchase_requests", "id=" + requestId + " AND seller_confirmed_version=7"));
    assertEquals(
        0,
        count("purchase_requests", "id=" + requestId + " AND buyer_confirmed_version IS NOT NULL"));
    assertEquals(
        "PENDING", text("SELECT request_status FROM purchase_requests WHERE id=" + otherId));
    assertEquals(0, count("audit_logs", "action IN ('OWNERSHIP_TRANSFERRED','TRADE_COMPLETED')"));
    assertDraftPreserved();
  }

  private void assertCompleted() {
    assertEquals(0, count("product_events", "product_id=101 AND visibility<>'PRIVATE'"));
    assertEquals("SOLD", listingStatus());
    assertEquals(2, owner());
    assertEquals(8, version());
    assertEquals(
        "COMPLETED",
        text("SELECT transaction_status FROM purchase_requests WHERE id=" + requestId));
    assertEquals(
        "REJECTED", text("SELECT request_status FROM purchase_requests WHERE id=" + otherId));
    assertEquals("COMPLETED", text("SELECT status FROM ownership_transfers"));
    assertEquals(0, count("ownership_transfers", "pending_slot IS NOT NULL"));
    assertEquals(
        1,
        count(
            "product_events",
            "event_type='TRANSFER' AND author_id IS NULL AND status='VALID' AND transfer_request_id IS NOT NULL"));
    assertEquals(1, count("product_events", "id=601 AND event_type='REPAIR'"));
    assertEquals(
        1,
        count(
            "documents",
            "id=501 AND uploader_id=1 AND protected_at IS NOT NULL AND object_key='masked-test-copy.png'"));
    assertEquals(0, count("listing_drafts", "product_id=101"));
    assertEquals(0, count("draft_images", "draft_id=" + draftId));
    assertEquals(4, count("product_images", "product_id=101"));
    assertEquals(1, count("audit_logs", "action='OWNERSHIP_TRANSFERRED'"));
  }

  private void assertDraftPreserved() {
    assertEquals(1, count("listing_drafts", "id=" + draftId));
    assertEquals(2, count("draft_images", "draft_id=" + draftId));
  }

  /** 격리 인증 대역의 새 회원 식별자로 실제 제품 암호화·파일 업로드와 프로필 DB 경로를 검증한다. */
  @Test
  void convergenceApiCreatesEncryptedProductDocumentsImagesAndProfile() throws Exception {
    long actor =
        jdbc.queryForObject(
            "insert into users(id,email,password_hash,nickname,role,email_verified,created_at,updated_at) select max(id)+1,'api-convergence@synthetic.invalid','isolated-auth-double','api-smoke','USER',true,now(),now() from users returning id",
            Long.class);
    String serial = "CONVERGENCE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
    var product =
        send(
            "POST",
            "/api/v1/products",
            actor,
            "{\"title\":\"convergence smoke\",\"manufacturer\":\"Synthetic\",\"modelName\":\"M\",\"serialNumber\":\""
                + serial
                + "\"}");
    assertEquals(201, product.statusCode(), product.body());
    long productId = data(product).path("productId").asLong();
    assertTrue(productId > 0);
    assertNotEquals(
        serial,
        jdbc.queryForObject(
            "select serial_number_ciphertext from products where id=?", String.class, productId));
    assertEquals(
        serial,
        context
            .getBean(SerialNumberProtector.class)
            .decrypt(
                jdbc.queryForObject(
                    "select serial_number_ciphertext from products where id=?",
                    String.class,
                    productId)));
    var buffer = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(
        new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB),
        "png",
        buffer);
    byte[] image = buffer.toByteArray();
    assertEquals(
        201,
        multipart("POST", "/api/v1/products/" + productId + "/images", actor, image).statusCode());
    var document =
        multipart(
            "POST",
            "/api/v1/products/" + productId + "/documents?maskingConfirmed=true",
            actor,
            image);
    assertEquals(201, document.statusCode(), document.body());
    assertEquals(204, multipart("PUT", "/api/v1/me/profile-image", actor, image).statusCode());
    assertEquals(1, count("user_profile_images", "user_id=" + actor));
    assertEquals(200, send("GET", "/api/v1/me/profile-image", actor, null).statusCode());
    assertEquals(200, send("GET", "/api/v1/products/" + productId, actor, null).statusCode());
    assertEquals(404, send("GET", "/api/v1/products/" + productId, 2, null).statusCode());
    assertEquals(204, send("DELETE", "/api/v1/products/" + productId, actor, null).statusCode());
  }

  /** 테스트 파일은 서비스 저장소를 통해 저장하며 요청에는 격리 JWT만 사용한다. */
  private static HttpResponse<String> multipart(
      String method, String path, long actor, byte[] content) throws Exception {
    String boundary = "convergenceBoundary";
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.write(
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    bytes.write(content);
    bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token(actor))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private long createRequest(long room, long actor) throws Exception {
    var response =
        send(
            "POST",
            "/api/v1/market/listings/11/purchase-requests",
            actor,
            "{\"roomId\":" + room + "}");
    assertEquals(201, response.statusCode(), response.body());
    return data(response).path("requestId").asLong();
  }

  private HttpResponse<String> accept() throws Exception {
    return send("POST", "/api/v1/purchase-requests/" + requestId + "/accept", 1, null);
  }

  private void reserve() throws Exception {
    var r = accept();
    assertEquals(201, r.statusCode(), r.body());
  }

  private HttpResponse<String> complete(long actor, long version) throws Exception {
    return send("POST", path("complete"), actor, "{\"confirmedFactsVersion\":" + version + "}");
  }

  private String path(String suffix) {
    return "/api/v1/trades/" + requestId + "/" + suffix;
  }

  private static void fail(String phase) {
    if (phase.equals(FAILURE.get()))
      throw new IllegalStateException("Injected " + phase + " write failure");
  }

  private static TransactionTemplate transaction() {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }

  private static long count(String table, String where) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
  }

  private static String text(String sql) {
    return jdbc.queryForObject(sql, String.class);
  }

  private static long owner() {
    return jdbc.queryForObject("SELECT owner_id FROM products WHERE id=101", Long.class);
  }

  private static long version() {
    return jdbc.queryForObject("SELECT facts_version FROM products WHERE id=101", Long.class);
  }

  private static String listingStatus() {
    return text("SELECT status FROM listings WHERE id=11");
  }

  /** 조회 허용뿐 아니라 이전 소유자의 특정 이력·증빙이 실제 응답으로 승계됐는지 확인한다. */
  private static void assertContainsId(
      HttpResponse<String> response, String field, long expectedId) {
    assertEquals(200, response.statusCode(), response.body());
    JsonNode items = data(response);
    assertTrue(items.isArray(), response.body());
    Set<Long> ids = new HashSet<>();
    items.forEach(item -> ids.add(item.path(field).asLong()));
    assertTrue(ids.contains(expectedId), response.body());
  }

  private static JsonNode data(HttpResponse<String> r) {
    return JSON.readTree(r.body()).path("data");
  }

  private static HttpResponse<String> send(String method, String path, long actor, String body)
      throws Exception {
    return async(method, path, actor, body).get(25, TimeUnit.SECONDS);
  }

  private static CompletableFuture<HttpResponse<String>> async(
      String method, String path, long actor, String body) throws Exception {
    return HTTP.sendAsync(
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token(actor))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String token(long actor) throws Exception {
    var e = Base64.getUrlEncoder().withoutPadding();
    String header =
        e.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload =
        e.encodeToString(
            ("{\"sub\":\"" + actor + "\",\"exp\":" + (Instant.now().getEpochSecond() + 3600) + "}")
                .getBytes(StandardCharsets.UTF_8));
    String body = header + "." + payload;
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return body + "." + e.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }
}
