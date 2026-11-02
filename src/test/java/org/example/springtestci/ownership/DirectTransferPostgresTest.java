package org.example.springtestci.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.example.springtestci.SpringTestCiApplication;
import org.example.springtestci.common.error.CommonException;
import org.example.springtestci.common.error.ErrorCode;
import org.example.springtestci.history.service.HistoryService;
import org.example.springtestci.listing.dto.SaveListingDraftRequest;
import org.example.springtestci.listing.service.ListingDraftService;
import org.example.springtestci.ownership.dto.AcceptTransferRequest;
import org.example.springtestci.ownership.dto.CreateTransferRequest;
import org.example.springtestci.ownership.service.OwnershipTransferService;
import org.example.springtestci.trade.service.PurchaseRequestService;
import org.example.springtestci.trade.service.TradeCancellationService;
import org.example.springtestci.trade.service.TradeCompletionService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 전용 PostgreSQL, 실제 HTTP와 !test 운영 Bean으로 외부 DIRECT 이전을 인수한다.
 *
 * <p>ownership·history·문서·거래 저장은 실제 구현이다. 실패 주입은 시험 스키마의 SQL 트리거만 사용하며 운영 포트를 대역으로 바꾸지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "OWN_INT_TEST_ENABLED", matches = "true")
class DirectTransferPostgresTest {
  private static final long PRODUCT_ID = 101L;
  private static final long LISTING_ID = 301L;
  private static final long REQUEST_ID = 701L;
  private static final String SCHEMA =
      "own_direct_" + UUID.randomUUID().toString().replace("-", "");
  private static ConfigurableApplicationContext context;
  private static JdbcTemplate admin;
  private static JdbcTemplate jdbc;
  private static DataSource source;
  private static TransactionTemplate transactions;
  private static PurchaseRequestService purchases;
  private static TradeCancellationService cancellations;
  private static TradeCompletionService completions;
  private static OwnershipTransferService direct;
  private static String[] applicationArguments;

  @BeforeAll
  static void startActualApplication() {
    String url = required("OWN_INT_TEST_DB_URL");
    if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/sale_ai_test")) {
      throw new IllegalArgumentException("Use a loopback sale_ai_test URL without parameters");
    }
    String user = required("OWN_INT_TEST_DB_USER");
    String password = required("OWN_INT_TEST_DB_PASSWORD");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    admin.execute("CREATE SCHEMA " + SCHEMA);
    String schemaUrl = url + "?currentSchema=" + SCHEMA;
    source = new DriverManagerDataSource(schemaUrl, user, password);
    Flyway.configure()
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(source)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    Flyway.configure()
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .dataSource(source)
        .locations(
            "classpath:db/migration",
            "classpath:db/pending/trade-p2",
            "classpath:db/pending/trade-p3")
        .outOfOrder(true)
        .load()
        .migrate();
    String redisHost = required("OWN_INT_TEST_REDIS_HOST");
    if (!List.of("127.0.0.1", "localhost").contains(redisHost)) {
      throw new IllegalArgumentException("Use a loopback dedicated Redis");
    }
    var application = new SpringApplication(SpringTestCiApplication.class);
    application.addPrimarySources(List.of(DeterministicTime.class));
    applicationArguments =
        new String[] {
          "--spring.profiles.active=local",
          "--spring.config.import=optional:classpath:ownership-integration-no-env.properties",
          "--spring.main.banner-mode=off",
          "--logging.level.root=WARN",
          "--server.address=127.0.0.1",
          "--server.port=0",
          "--spring.datasource.url=" + schemaUrl,
          "--spring.datasource.username=" + user,
          "--spring.datasource.password=" + password,
          "--spring.datasource.driver-class-name=org.postgresql.Driver",
          "--spring.jpa.hibernate.ddl-auto=validate",
          "--spring.jpa.properties.hibernate.default_schema=" + SCHEMA,
          "--spring.flyway.enabled=false",
          "--spring.data.redis.host=" + redisHost,
          "--spring.data.redis.port=" + required("OWN_INT_TEST_REDIS_PORT"),
          "--spring.data.redis.password=",
          "--spring.data.redis.ssl.enabled=false",
          "--app.features.chat-api.enabled=true",
          "--app.features.trade-purchase-requests.enabled=true",
          "--app.features.trade-p3.enabled=true",
          "--app.features.trade-p4.enabled=true",
          "--app.features.listing-history.enabled=true",
          "--app.ai.summary.enabled=false",
          "--app.ai.listing.enabled=false",
          "--app.ai.listing.api-key=",
          "--app.ai.gemini.api-key=",
          "--app.supabase.url=",
          "--app.supabase.secret=",
          "--app.jwt.secret=ownership-integration-synthetic-secret-at-least-32-bytes",
          "--app.product.serial-secret=ownership-integration-synthetic-serial-secret-32-bytes"
        };
    context = application.run(applicationArguments);
    jdbc = context.getBean(JdbcTemplate.class);
    transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    purchases = context.getBean(PurchaseRequestService.class);
    cancellations = context.getBean(TradeCancellationService.class);
    completions = context.getBean(TradeCompletionService.class);
    direct = context.getBean(OwnershipTransferService.class);
  }

  @AfterAll
  static void closeCreatedSchema() {
    if (context != null) context.close();
    if (admin != null) admin.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
  }

  @BeforeEach
  void fixtures() {
    testClock.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    committedEvents.set(0);
    // Redis 나가기 상태는 DB 스키마와 별도로 영속화되므로 재실행 전에 합성 참여자의 키만 초기화한다.
    var roomLeaves =
        context.getBean(org.example.springtestci.chat.repository.ChatRoomLeaveRepository.class);
    roomLeaves.clearLeft(501L, 1L);
    roomLeaves.clearLeft(501L, 2L);
    roomLeaves.clearLeft(502L, 1L);
    roomLeaves.clearLeft(502L, 3L);
    for (String table :
        List.of(
            "products",
            "product_events",
            "documents",
            "audit_logs",
            "listings",
            "listing_drafts",
            "draft_images",
            "ownership_transfers")) {
      jdbc.execute("DROP TRIGGER IF EXISTS own_int_fault ON " + table);
      jdbc.execute("DROP TRIGGER IF EXISTS direct_fault ON " + table);
    }
    jdbc.execute("DROP FUNCTION IF EXISTS own_int_fail()");
    jdbc.execute("DROP FUNCTION IF EXISTS direct_fail()");
    jdbc.execute(
        "TRUNCATE TABLE transaction_change_reviews, purchase_requests, chat_read_positions, chat_messages, chat_rooms, event_documents, product_events, document_extractions, documents, draft_images, listing_drafts, listing_images, listings, product_images, product_field_authors, laptop_details, audit_logs, ownership_transfers, products, users RESTART IDENTITY CASCADE");
    jdbc.update(
        "INSERT INTO users(id,email,password_hash,nickname,role,email_verified,created_at,updated_at) VALUES (1,'seller@own.invalid','unused','seller','USER',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(2,'buyer@own.invalid','unused','buyer','USER',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),(3,'other@own.invalid','unused','other','USER',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    jdbc.update(
        "INSERT INTO products(id,category,manufacturer,manufacturer_normalized,model_name,serial_number_fingerprint,title,owner_id,serial_number_ciphertext) VALUES (101,'LAPTOP','maker','MAKER','model','own-int-fingerprint','ownership integration',1,'unused'),(102,'LAPTOP','maker','MAKER','other','own-int-other','unrelated',3,'unused')");
    jdbc.update("INSERT INTO laptop_details(product_id) VALUES(101),(102)");
    jdbc.update(
        "INSERT INTO listings(id,product_id,seller_id,title,price,condition_grade,description,status,published_at,source_draft_id,source_draft_version,active_slot) VALUES(301,101,1,'integration listing',100000,4,'description','ACTIVE',CURRENT_TIMESTAMP,9001,0,1)");
    jdbc.update(
        "INSERT INTO chat_rooms(id,listing_id,seller_id,inquirer_id) VALUES(501,301,1,2),(502,301,1,3)");
    jdbc.update(
        "INSERT INTO purchase_requests(id,room_id,listing_id,request_status) VALUES(701,501,301,'PENDING'),(702,502,301,'PENDING')");
    jdbc.update(
        "INSERT INTO product_events(id,product_id,author_id,event_type,occurred_on,title,content) VALUES(801,101,1,'INSPECTION',CURRENT_DATE,'private inspection','private content'),(802,102,3,'INSPECTION',CURRENT_DATE,'other inspection','other private content')");
    jdbc.update(
        "INSERT INTO documents(id,product_id,uploader_id,object_key,content_type,byte_size,mask_confirmed_at) VALUES(901,101,1,'own-int-document','image/png',10,CURRENT_TIMESTAMP),(902,102,3,'other-document','image/png',10,CURRENT_TIMESTAMP)");
    jdbc.update("INSERT INTO event_documents(event_id,document_id) VALUES(801,901),(802,902)");
    jdbc.update("UPDATE listings SET status='CANCELLED',active_slot=NULL WHERE id=301");
    jdbc.update(
        "INSERT INTO product_images(id,product_id,storage_key,content_type,sort_order,is_primary) VALUES(1001,101,'original-101','image/png',0,true),(1002,102,'original-102','image/png',0,true)");
    jdbc.update(
        "INSERT INTO listing_images(listing_id,product_image_id,sort_order,is_primary) VALUES(301,1001,0,true)");
    context
        .getBean(ListingDraftService.class)
        .save(102, 3, draft("unrelated private draft", List.of(1002L)));
  }

  private static final MutableClock testClock = new MutableClock();
  private static final java.util.concurrent.atomic.AtomicInteger committedEvents =
      new java.util.concurrent.atomic.AtomicInteger();

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class DeterministicTime {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    java.time.Clock directTestClock() {
      return testClock;
    }

    @org.springframework.context.annotation.Bean
    EventProbe directEventProbe() {
      return new EventProbe();
    }
  }

  static class EventProbe {
    @org.springframework.transaction.event.TransactionalEventListener(
        phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void committed(org.example.springtestci.product.event.ProductFactsChanged event) {
      if (event.productId() == 101) committedEvents.incrementAndGet();
    }
  }

  static class MutableClock extends java.time.Clock {
    private volatile Instant now = Instant.now();

    void set(Instant value) {
      now = value;
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void p5A01ActualNonTestBeansUseSharedJpaJdbcTransactionManager() {
    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("local");
    assertThat(context.getBean(PlatformTransactionManager.class))
        .isInstanceOf(JpaTransactionManager.class);
    assertThat(AopUtils.getTargetClass(direct)).isEqualTo(OwnershipTransferService.class);
    assertThat(context.getBean(JpaTransactionManager.class).getDataSource())
        .isSameAs(jdbc.getDataSource());
    assertThat(context.getBeansOfType(OwnershipTransferService.class)).hasSize(1);
    assertThat(
            context.getBeansOfType(
                org.example.springtestci.trade.service.InternalTradeCompletionPort.class))
        .hasSize(1);
    assertThat(
            context.getBean(
                org.example.springtestci.trade.service.InternalTradeCompletionPort.class))
        .isInstanceOf(
            org.example.springtestci.ownership.service.InternalTradeOwnershipService.class);
  }

  @Test
  void p5A02IssuePersistsOnlyRandomHashExactProductAuditAndSingleSlot() {
    var first = issue(null);
    assertThat(first.productId()).isEqualTo(101);
    assertThat(first.rawToken()).matches("[0-9a-f]{64}");
    assertThat(first.linkUrl()).isEqualTo("/receive?token=" + first.rawToken());
    assertThat(first.expiresAt()).isEqualTo(testClock.instant().plus(Duration.ofHours(24)));
    assertThat(text("SELECT token_hash FROM ownership_transfers"))
        .isEqualTo(OwnershipTransferService.hashToken(first.rawToken()))
        .isNotEqualTo(first.rawToken());
    assertThat(number("SELECT pending_slot FROM ownership_transfers")).isEqualTo(1);
    assertThat(
            number(
                "SELECT COUNT(*) FROM audit_logs WHERE target_id="
                    + first.transferId()
                    + " AND actor_id=1 AND action='OWNERSHIP_TRANSFER_CREATED'"))
        .isEqualTo(1);
    var before = snapshot();
    error(() -> issue(null), ErrorCode.OWNERSHIP_ALREADY_PENDING);
    assertThat(snapshot()).isEqualTo(before);
    assertThat(snapshot().toString()).doesNotContain(first.rawToken());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missingProduct",
        "nonOwner",
        "selfRecipient",
        "missingRecipient",
        "purchaseRequest",
        "archived"
      })
  void p5A02RejectsInvalidIssuanceBeforeAnyWrites(String scenario) {
    if (scenario.equals("archived"))
      jdbc.update("UPDATE products SET archived_at=CURRENT_TIMESTAMP WHERE id=101");
    var before = snapshot();
    switch (scenario) {
      case "missingProduct" ->
          error(
              () -> direct.createTransfer(1, new CreateTransferRequest(999L, null, null)),
              ErrorCode.RESOURCE_NOT_FOUND);
      case "nonOwner" ->
          error(
              () -> direct.createTransfer(3, new CreateTransferRequest(101L, null, null)),
              ErrorCode.ACCESS_DENIED);
      case "selfRecipient" -> error(() -> issue(1L), ErrorCode.INVALID_INPUT);
      case "missingRecipient" -> error(() -> issue(999L), ErrorCode.RESOURCE_NOT_FOUND);
      case "purchaseRequest" ->
          error(
              () -> direct.createTransfer(1, new CreateTransferRequest(101L, 2L, 701L)),
              ErrorCode.INVALID_INPUT);
      case "archived" -> error(() -> issue(null), ErrorCode.RESOURCE_NOT_FOUND);
      default -> throw new AssertionError(scenario);
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "RESERVED"})
  void p5A02SaleBlocksIssuanceWithoutAutomaticCancellation(String status) {
    jdbc.update("UPDATE listings SET status=?,active_slot=1 WHERE id=301", status);
    var before = snapshot();
    error(() -> issue(null), ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"valid", "expired", "cancelled", "completed", "ownerChanged", "archived", "sale"})
  void p5A03VerifyIsReadOnlyAcrossEveryPublicTerminalAndUnavailableState(String state) {
    var transfer = issue(null);
    switch (state) {
      case "expired" -> testClock.set(transfer.expiresAt());
      case "cancelled" -> direct.cancelTransfer(1, transfer.transferId());
      case "completed" -> consentAndApprove(2, transfer.rawToken());
      case "ownerChanged" -> jdbc.update("UPDATE products SET owner_id=3 WHERE id=101");
      case "archived" ->
          jdbc.update("UPDATE products SET archived_at=CURRENT_TIMESTAMP WHERE id=101");
      case "sale" -> jdbc.update("UPDATE listings SET status='ACTIVE',active_slot=1 WHERE id=301");
      default -> {}
    }
    var before = snapshot();
    var verification = direct.verifyTransfer(2, transfer.rawToken());
    assertThat(verification.canAccept()).isEqualTo(state.equals("valid"));
    assertThat(verification.isExpired()).isEqualTo(state.equals("expired"));
    if (!state.equals("valid")) {
      assertThat(verification.productTitle()).isNull();
      assertThat(verification.fromUserNickname()).isNull();
    }
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void p5A03InvalidAndInternalTokensNeverExposeOrMutateInternalTransfer() {
    error(() -> direct.verifyTransfer(2, "not-a-token"), ErrorCode.OWNERSHIP_NOT_FOUND);
    jdbc.update("UPDATE listings SET status='ACTIVE',active_slot=1 WHERE id=301");
    purchases.accept(701, 1);
    String token = "synthetic-internal-not-issued";
    jdbc.update(
        "UPDATE ownership_transfers SET token_hash=?,expires_at=?",
        OwnershipTransferService.hashToken(token),
        java.sql.Timestamp.from(testClock.instant().minus(Duration.ofDays(2))));
    var before = snapshot();
    error(() -> direct.verifyTransfer(2, token), ErrorCode.OWNERSHIP_NOT_FOUND);
    error(
        () -> direct.acceptTransfer(2, new AcceptTransferRequest(token)),
        ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    error(
        () -> direct.cancelTransfer(1, number("SELECT id FROM ownership_transfers")),
        ErrorCode.OWNERSHIP_TRANSFER_BLOCKED);
    error(() -> issue(null), ErrorCode.OWNERSHIP_ALREADY_PENDING);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void p5A04OnlySenderMayCancelAndCancelledTokenCannotBeAccepted() {
    var transfer = issue(2L);
    var before = snapshot();
    error(() -> direct.cancelTransfer(2, transfer.transferId()), ErrorCode.ACCESS_DENIED);
    assertThat(snapshot()).isEqualTo(before);
    direct.cancelTransfer(1, transfer.transferId());
    assertThat(text("SELECT status FROM ownership_transfers")).isEqualTo("CANCELLED");
    assertThat(number("SELECT COUNT(*) FROM ownership_transfers WHERE pending_slot IS NOT NULL"))
        .isZero();
    error(() -> consentAndApprove(2, transfer.rawToken()), ErrorCode.OWNERSHIP_EXPIRED);
    assertThat(number("SELECT owner_id FROM products WHERE id=101")).isEqualTo(1);
    assertThat(
            number(
                "SELECT COUNT(*) FROM audit_logs WHERE action='OWNERSHIP_TRANSFER_CANCELLED' AND actor_id=1 AND target_id="
                    + transfer.transferId()))
        .isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(longs = {-1, 0, 1})
  void p5A04ExactExpiryBoundaryIsDeterministicAndReissueReleasesOnlyExpiredDirect(long nanos) {
    var first = issue(2L);
    testClock.set(first.expiresAt().plusNanos(nanos));
    assertThat(direct.verifyTransfer(2, first.rawToken()).isExpired()).isEqualTo(nanos >= 0);
    var before = snapshot();
    if (nanos < 0) {
      error(() -> issue(null), ErrorCode.OWNERSHIP_ALREADY_PENDING);
      assertThat(snapshot()).isEqualTo(before);
      consentAndApprove(2, first.rawToken());
      assertCompleted(first.transferId(), 2);
    } else {
      error(
          () -> direct.acceptTransfer(2, new AcceptTransferRequest(first.rawToken())),
          ErrorCode.OWNERSHIP_EXPIRED);
      assertThat(snapshot()).isEqualTo(before);
      var second = issue(3L);
      assertThat(second.transferId()).isNotEqualTo(first.transferId());
      assertThat(text("SELECT status FROM ownership_transfers WHERE id=" + first.transferId()))
          .isEqualTo("EXPIRED");
      error(
          () -> direct.acceptTransfer(2, new AcceptTransferRequest(first.rawToken())),
          ErrorCode.OWNERSHIP_EXPIRED);
      consentAndApprove(3, second.rawToken());
      assertCompleted(second.transferId(), 3);
    }
  }

  @Test
  void p5A05SpecifiedRecipientAndSelfRejectionsPreserveRightfulAcceptance() {
    var transfer = issue(2L);
    var before = snapshot();
    error(() -> consentAndApprove(1, transfer.rawToken()), ErrorCode.OWNERSHIP_SELF_ACCEPT);
    error(() -> consentAndApprove(3, transfer.rawToken()), ErrorCode.ACCESS_DENIED);
    assertThat(snapshot()).isEqualTo(before);
    consentAndApprove(2, transfer.rawToken());
    assertCompleted(transfer.transferId(), 2);
    var completed = snapshot();
    error(() -> consentAndApprove(2, transfer.rawToken()), ErrorCode.OWNERSHIP_EXPIRED);
    assertThat(snapshot()).isEqualTo(completed);
    assertThat(committedEvents.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ownerChanged", "archived", "sale"})
  void p5A05AcceptRechecksOwnershipArchiveAndNewSale(String state) {
    var transfer = issue(null);
    switch (state) {
      case "ownerChanged" -> jdbc.update("UPDATE products SET owner_id=3 WHERE id=101");
      case "archived" ->
          jdbc.update("UPDATE products SET archived_at=CURRENT_TIMESTAMP WHERE id=101");
      case "sale" -> jdbc.update("UPDATE listings SET status='ACTIVE',active_slot=1 WHERE id=301");
      default -> throw new AssertionError(state);
    }
    var before = snapshot();
    error(
        () -> consentAndApprove(2, transfer.rawToken()),
        state.equals("sale")
            ? ErrorCode.OWNERSHIP_TRANSFER_BLOCKED
            : state.equals("archived")
                ? ErrorCode.OWNERSHIP_NOT_FOUND
                : ErrorCode.OWNERSHIP_EXPIRED);
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test
  void p5A05ActualHttpRequiresAuthenticationAndCookieCsrfAndOwnerStatusHidesToken()
      throws Exception {
    var transfer = issue(2L);
    String accept = "{\"token\":\"" + transfer.rawToken() + "\"}";
    var before = snapshot();
    assertThat(
            http("POST", "/api/v1/ownership/transfers/accept", accept, 0, false, false)
                .statusCode())
        .isEqualTo(401);
    assertThat(
            http("POST", "/api/v1/ownership/transfers/accept", accept, 2, true, false).statusCode())
        .isEqualTo(403);
    assertThat(snapshot()).isEqualTo(before);
    var status = http("GET", "/api/v1/ownership/transfers/product/101", null, 1, false, false);
    assertThat(status.statusCode()).isEqualTo(200);
    assertThat(status.body())
        .contains("\"transferId\":" + transfer.transferId(), "\"status\":\"PENDING\"")
        .doesNotContain(transfer.rawToken(), "token_hash", "rawToken");
    assertThat(
            http("GET", "/api/v1/ownership/transfers/product/101", null, 3, false, false)
                .statusCode())
        .isEqualTo(403);
    assertThat(
            http("GET", "/api/v1/ownership/transfers/product/101", null, 0, false, false)
                .statusCode())
        .isEqualTo(401);
    var accepted = http("POST", "/api/v1/ownership/transfers/accept", accept, 2, true, true);
    assertThat(accepted.statusCode()).isEqualTo(200);
    assertThat(number("SELECT owner_id FROM products WHERE id=101")).isEqualTo(1);
    direct.approveTransfer(
        1,
        transfer.transferId(),
        new org.example.springtestci.ownership.dto.ApproveTransferRequest(
            2L, number("SELECT facts_version FROM products WHERE id=101")));
    assertCompleted(transfer.transferId(), 2);
    assertThat(
            http("GET", "/api/v1/ownership/transfers/product/101", null, 1, false, false)
                .statusCode())
        .isEqualTo(403);
    var verify =
        http(
            "GET",
            "/api/v1/ownership/transfers/verify?token=" + transfer.rawToken(),
            null,
            2,
            false,
            false);
    assertThat(verify.statusCode()).isEqualTo(200);
    assertThat(verify.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(verify.headers().firstValue("Referrer-Policy").orElse("")).isEqualTo("no-referrer");
    assertThat(verify.body())
        .doesNotContain(transfer.rawToken(), "private content", "own-int-document");
    var receive = http("GET", "/receive?token=" + transfer.rawToken(), null, 0, false, false);
    assertThat(receive.statusCode()).isEqualTo(200);
    assertThat(receive.headers().firstValue("Referrer-Policy").orElse("")).isEqualTo("no-referrer");
    assertThat(receive.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    assertThat(receive.body()).doesNotContain(transfer.rawToken());
  }

  @Test
  void p5A06CompletePreservesOriginalsAndUnrelatedDataAndNewOwnerCreatesFreshDraft() {
    var drafts = context.getBean(ListingDraftService.class);
    long oldDraft = drafts.save(101, 1, draft("seller private draft", List.of(1001L))).draftId();
    jdbc.update(
        "UPDATE documents SET protected_at=? WHERE id=901",
        java.sql.Timestamp.from(testClock.instant().minusSeconds(60)));
    var preserved = preserved();
    var transfer = issue(null);
    consentAndApprove(2, transfer.rawToken());
    assertCompleted(transfer.transferId(), 2);
    assertThat(preserved()).isEqualTo(preserved);
    assertThat(number("SELECT COUNT(*) FROM listing_drafts WHERE id=" + oldDraft)).isZero();
    assertThat(number("SELECT COUNT(*) FROM draft_images WHERE product_image_id=1001")).isZero();
    assertThat(
            number(
                "SELECT COUNT(*) FROM product_events WHERE event_type='TRANSFER' AND author_id IS NULL"))
        .isEqualTo(1);
    assertThat(number("SELECT transfer_request_id FROM product_events WHERE event_type='TRANSFER'"))
        .isEqualTo(transfer.transferId());
    assertThat(number("SELECT COUNT(*) FROM documents WHERE id=901 AND protected_at IS NOT NULL"))
        .isEqualTo(1);
    assertThat(
            number(
                "SELECT COUNT(*) FROM audit_logs WHERE action='OWNERSHIP_TRANSFERRED' AND actor_id=1 AND target_id="
                    + transfer.transferId()))
        .isEqualTo(1);
    assertThat(
            number("SELECT COUNT(*) FROM audit_logs WHERE action='INTERNAL_OWNERSHIP_COMPLETED'"))
        .isZero();
    assertThat(text("SELECT status FROM listings WHERE id=301")).isEqualTo("CANCELLED");
    assertThat(text("SELECT request_status FROM purchase_requests WHERE id=701"))
        .isEqualTo("PENDING");
    var fresh = drafts.save(101, 2, draft("new owner fresh draft", List.of(1001L)));
    assertThat(fresh.draftId()).isNotEqualTo(oldDraft);
    assertThat(fresh.title()).isEqualTo("new owner fresh draft");
    assertThat(context.getBean(HistoryService.class).timeline(2, 101))
        .filteredOn(event -> event.eventId() == 801)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.content()).isEqualTo("private content");
              assertThat(event.documentIds()).containsExactly(901L);
            });
    assertThat(
            context
                .getBean(org.example.springtestci.document.service.DocumentService.class)
                .list(101L, 2L))
        .extracting(org.example.springtestci.document.entity.Document::getId)
        .containsExactly(901L);
    assertThat(
            number(
                "SELECT COUNT(*) FROM product_events WHERE product_id=101 AND visibility<>'PRIVATE'"))
        .isZero();
    assertThat(committedEvents.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "products",
        "ownership_transfers",
        "product_events",
        "documents",
        "listing_drafts",
        "draft_images",
        "audit_logs",
        "commit"
      })
  void p5A07ActualSqlAndDeferredCommitFailuresRollbackEverythingAndAllowRetry(String boundary) {
    context
        .getBean(ListingDraftService.class)
        .save(101, 1, draft("seller private draft", List.of(1001L)));
    var transfer = issue(2L);
    direct.acceptTransfer(2, new AcceptTransferRequest(transfer.rawToken()));
    var before = snapshot();
    String table = boundary.equals("commit") ? "ownership_transfers" : boundary;
    String event =
        List.of("product_events", "audit_logs").contains(table)
            ? "INSERT"
            : List.of("listing_drafts", "draft_images").contains(table) ? "DELETE" : "UPDATE";
    installFault(table, event, boundary.equals("commit"));
    Throwable failure =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> consentAndApprove(2, transfer.rawToken()));
    assertInjectedSqlFailure(failure);
    assertThat(snapshot()).isEqualTo(before);
    assertThat(committedEvents.get()).isZero();
    jdbc.execute("DROP TRIGGER direct_fault ON " + table);
    jdbc.execute("DROP FUNCTION direct_fail()");
    consentAndApprove(2, transfer.rawToken());
    assertCompleted(transfer.transferId(), 2);
    assertThat(committedEvents.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"secondAccept", "cancel", "create", "draft", "sale", "internal"})
  void p5A08CompletionFirstIndependentWorkersObserveCommittedState(String competitor)
      throws Exception {
    var transfer = issue(null);
    runBlockedRace(
        () -> consentAndApprove(2, transfer.rawToken()),
        () -> {
          switch (competitor) {
            case "secondAccept" ->
                error(() -> consentAndApprove(3, transfer.rawToken()), ErrorCode.OWNERSHIP_EXPIRED);
            case "cancel" ->
                assertThatThrownBy(() -> direct.cancelTransfer(1, transfer.transferId()))
                    .isInstanceOfSatisfying(
                        CommonException.class,
                        failure ->
                            assertThat(failure.errorCode())
                                .isIn(ErrorCode.INVALID_INPUT, ErrorCode.OWNERSHIP_EXPIRED));
            case "create" -> error(() -> issue(null), ErrorCode.RESOURCE_NOT_FOUND);
            case "draft" ->
                error(
                    () ->
                        context
                            .getBean(ListingDraftService.class)
                            .save(101, 1, draft("old owner raced", List.of())),
                    ErrorCode.RESOURCE_NOT_FOUND);
            case "sale" ->
                error(
                    () ->
                        context
                            .getBean(
                                org.example.springtestci.listing.service.ListingPublishService
                                    .class)
                            .publish(
                                101,
                                1,
                                new org.example.springtestci.listing.dto.PublishListingRequest(
                                    999L, 0L)),
                    ErrorCode.RESOURCE_NOT_FOUND);
            case "internal" -> error(() -> purchases.accept(701, 1), ErrorCode.TRADE_CONFLICT);
            default -> throw new AssertionError(competitor);
          }
        });
    assertCompleted(transfer.transferId(), 2);
    assertThat(committedEvents.get()).isEqualTo(1);
  }

  @Test
  void p5A08CancellationFirstLeavesNoCompletionEffects() throws Exception {
    var transfer = issue(null);
    runBlockedRace(
        () -> direct.cancelTransfer(1, transfer.transferId()),
        () -> error(() -> consentAndApprove(2, transfer.rawToken()), ErrorCode.OWNERSHIP_EXPIRED));
    assertThat(text("SELECT status FROM ownership_transfers")).isEqualTo("CANCELLED");
    assertThat(number("SELECT owner_id FROM products WHERE id=101")).isEqualTo(1);
    assertThat(number("SELECT COUNT(*) FROM product_events WHERE event_type='TRANSFER'")).isZero();
    assertThat(committedEvents.get()).isZero();
  }

  @Test
  void p5A08DraftFirstIsDeletedByCompletionWithoutDeletingOriginalPhoto() throws Exception {
    var transfer = issue(null);
    runBlockedRace(
        () ->
            context
                .getBean(ListingDraftService.class)
                .save(101, 1, draft("seller raced draft", List.of(1001L))),
        () -> consentAndApprove(2, transfer.rawToken()));
    assertCompleted(transfer.transferId(), 2);
    assertThat(number("SELECT COUNT(*) FROM listing_drafts WHERE product_id=101")).isZero();
    assertThat(number("SELECT COUNT(*) FROM product_images WHERE id=1001")).isEqualTo(1);
  }

  @Test
  void p5A08ExpiredReissueFirstRejectsOldTokenWithoutStealingNewSlot() throws Exception {
    var expired = issue(null);
    testClock.set(expired.expiresAt());
    runBlockedRace(
        () -> issue(3L),
        () -> error(() -> consentAndApprove(2, expired.rawToken()), ErrorCode.OWNERSHIP_EXPIRED));
    assertThat(text("SELECT status FROM ownership_transfers WHERE id=" + expired.transferId()))
        .isEqualTo("EXPIRED");
    assertThat(
            number(
                "SELECT COUNT(*) FROM ownership_transfers WHERE status='PENDING' AND pending_slot=1 AND to_user_id=3"))
        .isEqualTo(1);
    assertThat(number("SELECT owner_id FROM products WHERE id=101")).isEqualTo(1);
    assertThat(number("SELECT COUNT(*) FROM product_events WHERE event_type='TRANSFER'")).isZero();
    assertThat(committedEvents.get()).isZero();
  }

  @Test
  void p5A08RealInternalReservationFirstPreservesItsSlotAgainstDirectIssuance() throws Exception {
    jdbc.update("UPDATE listings SET status='ACTIVE',active_slot=1 WHERE id=301");
    runBlockedRace(
        () -> purchases.accept(701, 1),
        () -> error(() -> issue(null), ErrorCode.OWNERSHIP_ALREADY_PENDING));
    assertThat(
            number(
                "SELECT COUNT(*) FROM ownership_transfers WHERE transfer_type='INTERNAL' AND purchase_request_id=701 AND status='PENDING' AND pending_slot=1"))
        .isEqualTo(1);
    assertThat(text("SELECT status FROM listings WHERE id=301")).isEqualTo("RESERVED");
    assertThat(number("SELECT owner_id FROM products WHERE id=101")).isEqualTo(1);
    assertThat(number("SELECT COUNT(*) FROM product_events WHERE event_type='TRANSFER'")).isZero();
    assertThat(committedEvents.get()).isZero();
  }

  @Test
  void p5A08ActualPublicationFirstBlocksDirectIssuanceWithoutCancellingSale() throws Exception {
    var draft =
        context
            .getBean(ListingDraftService.class)
            .save(
                101,
                1,
                new SaveListingDraftRequest(
                    "publish race",
                    "actual listing description",
                    new java.math.BigDecimal("100000"),
                    4,
                    List.of(1001L),
                    null));
    var request =
        new org.example.springtestci.listing.dto.PublishListingRequest(
            draft.draftId(), draft.version());
    runBlockedRace(
        () ->
            context
                .getBean(org.example.springtestci.listing.service.ListingPublishService.class)
                .publish(101, 1, request),
        () -> error(() -> issue(null), ErrorCode.OWNERSHIP_TRANSFER_BLOCKED));
    assertThat(number("SELECT COUNT(*) FROM listings WHERE product_id=101 AND status='ACTIVE'"))
        .isEqualTo(1);
    assertThat(number("SELECT COUNT(*) FROM ownership_transfers")).isZero();
    assertThat(number("SELECT owner_id FROM products WHERE id=101")).isEqualTo(1);
    assertThat(number("SELECT COUNT(*) FROM product_events WHERE event_type='TRANSFER'")).isZero();
    assertThat(committedEvents.get()).isZero();
  }

  private void runBlockedRace(Runnable first, Runnable second) throws Exception {
    var acquired = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var firstResult =
          pool.submit(
              () ->
                  transactions.executeWithoutResult(
                      ignored -> {
                        jdbc.queryForObject(
                            "SELECT id FROM products WHERE id=101 FOR UPDATE", Long.class);
                        first.run();
                        acquired.countDown();
                        await(release);
                      }));
      assertThat(acquired.await(15, TimeUnit.SECONDS)).isTrue();
      var secondResult =
          pool.submit(
              () -> {
                entered.countDown();
                second.run();
              });
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(150);
      assertThat(secondResult.isDone()).isFalse();
      release.countDown();
      firstResult.get(15, TimeUnit.SECONDS);
      secondResult.get(15, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
      assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "P5_UI_ENABLED", matches = "true")
  void p5A09BrowserFlow() throws Exception {
    String password = "Synthetic-P5-only-123!";
    String hash =
        context
            .getBean(org.springframework.security.crypto.password.PasswordEncoder.class)
            .encode(password);
    jdbc.update("UPDATE users SET email='seller@p5.invalid',password_hash=? WHERE id=1", hash);
    jdbc.update("UPDATE users SET email='buyer@p5.invalid',password_hash=? WHERE id=2", hash);
    jdbc.update(
        "INSERT INTO products(id,category,manufacturer,manufacturer_normalized,model_name,serial_number_fingerprint,title,owner_id,serial_number_ciphertext) VALUES(103,'LAPTOP','maker','MAKER','expired product','p5-expired-ui','expired UI product',1,'unused'),(104,'LAPTOP','maker','MAKER','cancelled product','p5-cancelled-ui','cancelled UI product',1,'unused')");
    jdbc.update("INSERT INTO laptop_details(product_id) VALUES(103),(104)");
    var serials =
        context.getBean(org.example.springtestci.product.service.SerialNumberProtector.class);
    for (long productId : new long[] {101, 102, 103, 104}) {
      String serial = "P5-UI-" + productId;
      jdbc.update(
          "UPDATE products SET serial_number_ciphertext=?,serial_number_fingerprint=? WHERE id=?",
          serials.encrypt(serial),
          serials.fingerprint(serial),
          productId);
    }
    var expired = direct.createTransfer(1, new CreateTransferRequest(103L, null, null));
    jdbc.update(
        "UPDATE ownership_transfers SET expires_at=? WHERE id=?",
        java.sql.Timestamp.from(testClock.instant().minusSeconds(60)),
        expired.transferId());
    var cancelled = direct.createTransfer(1, new CreateTransferRequest(104L, null, null));
    direct.cancelTransfer(1, cancelled.transferId());
    var process = new ProcessBuilder(required("P5_UI_NODE"), required("P5_UI_SCRIPT"));
    var environment = process.environment();
    var preserved = new java.util.HashMap<String, String>();
    for (String name :
        List.of(
            "SystemRoot",
            "WINDIR",
            "PATH",
            "TEMP",
            "TMP",
            "USERPROFILE",
            "LOCALAPPDATA",
            "APPDATA",
            "COMSPEC",
            "PLAYWRIGHT_BROWSERS_PATH",
            "NODE_PATH")) {
      String value = System.getenv(name);
      if (value != null) preserved.put(name, value);
    }
    environment.clear();
    environment.putAll(preserved);
    environment.put(
        "P5_UI_BASE_URL",
        "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
    environment.put("P5_UI_EVIDENCE", required("P5_UI_EVIDENCE"));
    environment.put("P5_UI_EXPIRED_TOKEN", expired.rawToken());
    environment.put("P5_UI_CANCELLED_TOKEN", cancelled.rawToken());
    environment.put("P5_UI_SELLER_EMAIL", "seller@p5.invalid");
    environment.put("P5_UI_BUYER_EMAIL", "buyer@p5.invalid");
    environment.put("P5_UI_PASSWORD", password);
    process.redirectOutput(ProcessBuilder.Redirect.DISCARD);
    process.redirectError(ProcessBuilder.Redirect.DISCARD);
    Process running = process.start();
    try {
      assertThat(running.waitFor(240, TimeUnit.SECONDS))
          .as("Actual local browser flow must finish within its bounded deadline")
          .isTrue();
      assertThat(running.exitValue())
          .as("Actual local browser flow exit code; inspect redacted UI evidence")
          .isZero();
    } finally {
      if (running.isAlive()) running.destroyForcibly();
    }
    long transferId =
        number("SELECT id FROM ownership_transfers WHERE product_id=101 AND status='COMPLETED'");
    assertCompleted(transferId, 2);
    assertThat(
            number(
                "SELECT COUNT(*) FROM ownership_transfers WHERE product_id=101 AND status='COMPLETED'"))
        .isEqualTo(1);
    assertThat(number("SELECT owner_id FROM products WHERE id=103")).isEqualTo(1);
    assertThat(number("SELECT owner_id FROM products WHERE id=104")).isEqualTo(1);
    assertThat(
            number(
                "SELECT COUNT(*) FROM product_events WHERE product_id IN(103,104) AND event_type='TRANSFER'"))
        .isZero();
    assertThat(text("SELECT status FROM ownership_transfers WHERE product_id=104"))
        .isEqualTo("CANCELLED");
    assertThat(committedEvents.get()).isEqualTo(1);
  }

  @Test
  void inheritedMaskedCopiesKeepOwnerAccessRedactionAndDeletionConstraints() throws Exception {
    jdbc.update("UPDATE product_events SET visibility='PUBLIC' WHERE id=801");
    jdbc.update("UPDATE documents SET redaction_required_at=CURRENT_TIMESTAMP WHERE id=901");
    var transfer = issue(null);
    consentAndApprove(2, transfer.rawToken());
    assertThat(http("GET", "/api/v1/products/101/documents", null, 2, false, false).statusCode())
        .isEqualTo(200);
    assertThat(http("GET", "/api/v1/documents/901/file-url", null, 2, false, false).statusCode())
        .isEqualTo(ErrorCode.DOCUMENT_REDACTION_REQUIRED.status().value());
    assertThat(http("DELETE", "/api/v1/documents/901", null, 2, false, false).statusCode())
        .isEqualTo(ErrorCode.DOCUMENT_DELETE_NOT_ALLOWED.status().value());
    assertThat(http("GET", "/api/v1/documents/901/file-url", null, 1, false, false).statusCode())
        .isEqualTo(404);
    jdbc.update("UPDATE documents SET redaction_required_at=NULL WHERE id=901");
    assertThat(http("GET", "/api/v1/documents/901/file-url", null, 2, false, false).statusCode())
        .isEqualTo(200);
    assertThat(text("SELECT visibility FROM product_events WHERE id=801")).isEqualTo("PRIVATE");
    assertThat(number("SELECT uploader_id FROM documents WHERE id=901")).isEqualTo(1);
  }

  private void consentAndApprove(long recipientId, String token) {
    var consent = direct.acceptTransfer(recipientId, new AcceptTransferRequest(token));
    long ownerId =
        number("SELECT from_user_id FROM ownership_transfers WHERE id=" + consent.transferId());
    long factsVersion =
        number("SELECT facts_version FROM products WHERE id=" + consent.productId());
    direct.approveTransfer(
        ownerId,
        consent.transferId(),
        new org.example.springtestci.ownership.dto.ApproveTransferRequest(
            recipientId, factsVersion));
  }

  @Test
  void consentPreservesOwnershipFactsEvidenceDraftAndPublicationUntilApproval() {
    context
        .getBean(ListingDraftService.class)
        .save(101, 1, draft("pending owner draft", List.of(1001L)));
    var transfer = issue(null);
    var productBefore = jdbc.queryForMap("SELECT * FROM products WHERE id=101");
    var evidenceBefore = jdbc.queryForList("SELECT * FROM documents ORDER BY id");
    var historyBefore = jdbc.queryForList("SELECT * FROM product_events ORDER BY id");
    var draftsBefore = jdbc.queryForList("SELECT * FROM listing_drafts ORDER BY id");
    var consent = direct.acceptTransfer(2, new AcceptTransferRequest(transfer.rawToken()));
    assertThat(direct.acceptTransfer(2, new AcceptTransferRequest(transfer.rawToken())))
        .isEqualTo(consent);
    assertThat(jdbc.queryForMap("SELECT * FROM products WHERE id=101")).isEqualTo(productBefore);
    assertThat(jdbc.queryForList("SELECT * FROM documents ORDER BY id")).isEqualTo(evidenceBefore);
    assertThat(jdbc.queryForList("SELECT * FROM product_events ORDER BY id"))
        .isEqualTo(historyBefore);
    assertThat(jdbc.queryForList("SELECT * FROM listing_drafts ORDER BY id"))
        .isEqualTo(draftsBefore);
    assertThat(text("SELECT status FROM ownership_transfers")).isEqualTo("PENDING");
    assertThat(committedEvents.get()).isZero();
    error(() -> direct.verifyTransfer(3, transfer.rawToken()), ErrorCode.ACCESS_DENIED);
    var approval =
        new org.example.springtestci.ownership.dto.ApproveTransferRequest(
            2L, number("SELECT facts_version FROM products WHERE id=101"));
    var result = direct.approveTransfer(1, transfer.transferId(), approval);
    var completed = snapshot();
    assertThat(direct.approveTransfer(1, transfer.transferId(), approval)).isEqualTo(result);
    assertThat(snapshot()).isEqualTo(completed);
    assertCompleted(transfer.transferId(), 2);
    assertThat(committedEvents.get()).isEqualTo(1);
  }

  @Test
  void approvalRejectsChangedFactsAndOwnerAndConsentDoesNotExtendExpiry() {
    var transfer = issue(2L);
    long version = number("SELECT facts_version FROM products WHERE id=101");
    direct.acceptTransfer(2, new AcceptTransferRequest(transfer.rawToken()));
    var request = new org.example.springtestci.ownership.dto.ApproveTransferRequest(2L, version);
    jdbc.update("UPDATE products SET facts_version=facts_version+1 WHERE id=101");
    error(() -> direct.approveTransfer(1, transfer.transferId(), request), ErrorCode.INVALID_INPUT);
    jdbc.update("UPDATE products SET owner_id=3 WHERE id=101");
    error(() -> direct.approveTransfer(1, transfer.transferId(), request), ErrorCode.ACCESS_DENIED);
    jdbc.update("UPDATE products SET owner_id=1 WHERE id=101");
    testClock.set(transfer.expiresAt());
    error(
        () -> direct.approveTransfer(1, transfer.transferId(), request),
        ErrorCode.OWNERSHIP_EXPIRED);
    assertThat(number("SELECT COUNT(*) FROM product_events WHERE event_type='TRANSFER'")).isZero();
    assertThat(committedEvents.get()).isZero();
  }

  @Test
  void acceptedRequestCanBeCancelledAndReissued() {
    var transfer = issue(2L);
    direct.acceptTransfer(2, new AcceptTransferRequest(transfer.rawToken()));
    direct.cancelTransfer(1, transfer.transferId());
    error(
        () -> direct.acceptTransfer(2, new AcceptTransferRequest(transfer.rawToken())),
        ErrorCode.OWNERSHIP_EXPIRED);
    var replacement = issue(3L);
    assertThat(replacement.transferId()).isNotEqualTo(transfer.transferId());
    assertThat(number("SELECT COUNT(*) FROM ownership_transfers WHERE pending_slot=1"))
        .isEqualTo(1);
  }

  @Test
  void approvalCommittingBeforeUploadRejectsOldOwnerWithoutSavingFile() throws Exception {
    var transfer = issue(2L);
    direct.acceptTransfer(2, new AcceptTransferRequest(transfer.rawToken()));
    var request =
        new org.example.springtestci.ownership.dto.ApproveTransferRequest(
            2L, number("SELECT facts_version FROM products WHERE id=101"));
    runBlockedRace(
        () -> direct.approveTransfer(1, transfer.transferId(), request),
        () ->
            error(
                () ->
                    context
                        .getBean(org.example.springtestci.document.service.DocumentService.class)
                        .upload(
                            101L,
                            1L,
                            true,
                            "masked.pdf",
                            "application/pdf",
                            "%PDF-1.7 synthetic".getBytes()),
                ErrorCode.ACCESS_DENIED));
    assertThat(number("SELECT COUNT(*) FROM documents WHERE product_id=101")).isEqualTo(1);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Race release timed out");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    }
  }

  @Test
  void postgresFileDownloadRejectsFormerOwnerReplacedVersionAndDirectStorageKey() throws Exception {
    var storage = context.getBean(org.example.springtestci.common.storage.FileStorage.class);
    var documents =
        context.getBean(org.example.springtestci.document.service.DocumentService.class);
    byte[] content =
        "%PDF-1.7 synthetic document".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String originalKey =
        storage.store("documents/101", "pdf", new java.io.ByteArrayInputStream(content));
    try {
      jdbc.update(
          "UPDATE documents SET object_key=?,content_type='application/pdf',byte_size=? WHERE id=901",
          originalKey,
          content.length);
      String oldUrl = documents.createFileUrl(901L, 1L).url();
      assertThat(http("GET", oldUrl, null, 1, false, false).statusCode()).isEqualTo(200);
      assertThat(http("GET", oldUrl, null, 0, false, false).statusCode()).isEqualTo(401);
      assertThat(http("GET", oldUrl, null, 3, false, false).statusCode()).isEqualTo(403);
      String legacy = storage.createTemporaryUrl(originalKey, testClock.instant().plusSeconds(300));
      assertThat(http("GET", legacy, null, 1, false, false).statusCode()).isEqualTo(403);
      var transfer = issue(2L);
      consentAndApprove(2, transfer.rawToken());
      assertThat(http("GET", oldUrl, null, 1, false, false).statusCode()).isEqualTo(404);
      assertThat(http("GET", oldUrl, null, 2, false, false).statusCode()).isEqualTo(403);
      String ownerUrl = documents.createFileUrl(901L, 2L).url();
      assertThat(http("GET", ownerUrl, null, 2, false, false).statusCode()).isEqualTo(200);
      documents.replace(901L, 2L, true, "masked.pdf", "application/pdf", content);
      assertThat(
              number(
                  "SELECT COUNT(*) FROM document_replacements WHERE document_id=901 AND from_version=1 AND to_version=2"))
          .isEqualTo(1);
      assertThat(http("GET", ownerUrl, null, 2, false, false).statusCode()).isEqualTo(403);
      String replacedUrl = documents.createFileUrl(901L, 2L).url();
      assertThat(http("GET", replacedUrl, null, 2, false, false).statusCode()).isEqualTo(200);
      jdbc.update("UPDATE documents SET redaction_required_at=CURRENT_TIMESTAMP WHERE id=901");
      assertThat(http("GET", replacedUrl, null, 2, false, false).statusCode()).isEqualTo(409);
    } finally {
      storage.delete(originalKey);
      storage.delete(text("SELECT object_key FROM documents WHERE id=901"));
    }
  }

  private org.example.springtestci.ownership.dto.CreateTransferResponse issue(Long recipient) {
    return direct.createTransfer(1, new CreateTransferRequest(101L, recipient, null));
  }

  private SaveListingDraftRequest draft(String title, List<Long> images) {
    return new SaveListingDraftRequest(title, null, null, null, images, null);
  }

  private void assertCompleted(long transferId, long receiver) {
    assertThat(number("SELECT owner_id FROM products WHERE id=101")).isEqualTo(receiver);
    assertThat(number("SELECT facts_version FROM products WHERE id=101")).isEqualTo(2);
    assertThat(
            number(
                "SELECT COUNT(*) FROM ownership_transfers WHERE id="
                    + transferId
                    + " AND status='COMPLETED' AND to_user_id="
                    + receiver
                    + " AND pending_slot IS NULL AND completed_at IS NOT NULL"))
        .isEqualTo(1);
    assertThat(
            number(
                "SELECT COUNT(*) FROM product_events WHERE product_id=101 AND event_type='TRANSFER' AND transfer_request_id="
                    + transferId))
        .isEqualTo(1);
    assertThat(number("SELECT COUNT(*) FROM product_events WHERE event_type='TRANSFER'"))
        .isEqualTo(1);
    assertThat(number("SELECT COUNT(*) FROM documents WHERE id=901 AND protected_at IS NOT NULL"))
        .isEqualTo(1);
    assertThat(number("SELECT COUNT(*) FROM documents WHERE id=902 AND protected_at IS NULL"))
        .isEqualTo(1);
    assertThat(
            number(
                "SELECT COUNT(*) FROM audit_logs WHERE action='OWNERSHIP_TRANSFERRED' AND target_id="
                    + transferId
                    + " AND actor_id=1"))
        .isEqualTo(1);
  }

  private List<String> snapshot() {
    return List.of(
            "products",
            "ownership_transfers",
            "product_events",
            "documents",
            "event_documents",
            "product_images",
            "listing_images",
            "listing_drafts",
            "draft_images",
            "listings",
            "purchase_requests",
            "audit_logs")
        .stream()
        .flatMap(
            table ->
                jdbc
                    .queryForList(
                        "SELECT row_to_json(r)::text FROM "
                            + table
                            + " r ORDER BY row_to_json(r)::text",
                        String.class)
                    .stream())
        .toList();
  }

  private List<String> preserved() {
    return List.of(
            "SELECT row_to_json(r)::text FROM product_images r ORDER BY id",
            "SELECT row_to_json(r)::text FROM listing_images r ORDER BY listing_id,sort_order",
            "SELECT row_to_json(r)::text FROM event_documents r ORDER BY event_id,document_id",
            "SELECT row_to_json(r)::text FROM product_events r WHERE id IN(801,802) ORDER BY id",
            "SELECT row_to_json(r)::text FROM documents r ORDER BY id",
            "SELECT row_to_json(r)::text FROM products r WHERE id=102",
            "SELECT row_to_json(r)::text FROM listing_drafts r WHERE product_id=102",
            "SELECT row_to_json(r)::text FROM draft_images r JOIN listing_drafts d ON d.id=r.draft_id WHERE d.product_id=102")
        .stream()
        .flatMap(sql -> jdbc.queryForList(sql, String.class).stream())
        .toList();
  }

  private void error(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run)
        .isInstanceOf(CommonException.class)
        .hasFieldOrPropertyWithValue("errorCode", expected);
  }

  private void installFault(String table, String event, boolean deferred) {
    jdbc.execute(
        "CREATE FUNCTION direct_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic direct boundary failure'; END $$");
    jdbc.execute(
        (deferred ? "CREATE CONSTRAINT TRIGGER" : "CREATE TRIGGER")
            + " direct_fault "
            + (deferred ? "AFTER" : "BEFORE")
            + " "
            + event
            + " ON "
            + table
            + (deferred ? " DEFERRABLE INITIALLY DEFERRED" : "")
            + " FOR EACH ROW EXECUTE FUNCTION direct_fail()");
  }

  private void assertInjectedSqlFailure(Throwable failure) {
    assertThat(failure).isNotNull();
    Throwable root = failure;
    while (root.getCause() != null) root = root.getCause();
    assertThat(root).isInstanceOf(java.sql.SQLException.class);
    assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo("P0001");
    assertThat(root.getMessage()).contains("synthetic direct boundary failure");
  }

  private long number(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private String text(String sql) {
    return jdbc.queryForObject(sql, String.class);
  }

  private HttpResponse<String> http(
      String method, String path, String body, long actor, boolean cookie, boolean csrf)
      throws Exception {
    String port = context.getEnvironment().getProperty("local.server.port");
    var builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15));
    if (actor > 0) {
      Instant now = Instant.now();
      String jwt =
          context
              .getBean(JwtEncoder.class)
              .encode(
                  JwtEncoderParameters.from(
                      JwsHeader.with(MacAlgorithm.HS256).build(),
                      JwtClaimsSet.builder()
                          .subject(Long.toString(actor))
                          .issuedAt(now)
                          .expiresAt(now.plusSeconds(300))
                          .build()))
              .getTokenValue();
      if (cookie) {
        builder.header(
            "Cookie", "access_token=" + jwt + (csrf ? "; XSRF-TOKEN=synthetic-direct-csrf" : ""));
        if (csrf) builder.header("X-XSRF-TOKEN", "synthetic-direct-csrf");
      } else builder.header("Authorization", "Bearer " + jwt);
    } else if (method.equals("POST")) builder.header("Authorization", "Bearer invalid-synthetic");
    if (body != null) builder.header("Content-Type", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Missing dedicated test variable: " + name);
    return value;
  }
}
