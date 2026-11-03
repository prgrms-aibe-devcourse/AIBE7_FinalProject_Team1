package org.example.springtestci.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 사본을 교체한 적 있는 문서를 삭제할 때 교체 기록 외래키 때문에 실패하지 않는지 PostgreSQL에서 확인한다.
 *
 * <p>Mockito 단위 테스트({@code DocumentServiceTest})는 외래키를 검사하지 못해 이 문제를 잡지 못한다. 서비스·JPA 저장소·Flyway
 * 마이그레이션은 실제 구현이고 제품 소유자 조회만 시험 대역이다. 실행마다 임의 이름의 스키마를 만들고 끝나면 지운다.
 *
 * <p>실행: {@code DOC_LOCK_TEST_ENABLED=true}와 {@code DOC_LOCK_TEST_DB_URL}, {@code
 * DOC_LOCK_TEST_DB_USER}, {@code DOC_LOCK_TEST_DB_PASSWORD}를 설정한다. 설정이 없으면 건너뛴다.
 */
@EnabledIfEnvironmentVariable(named = "DOC_LOCK_TEST_ENABLED", matches = "true")
@SpringBootTest(
    properties = {
      "app.features.chat-api.enabled=false",
      "spring.flyway.enabled=false",
      "spring.datasource.driver-class-name=org.postgresql.Driver",
      "spring.jpa.hibernate.ddl-auto=none"
    })
class DocumentDeletePostgresTest {

  private static final String SCHEMA =
      "doc_delete_" + UUID.randomUUID().toString().replace("-", "");
  private static final long OWNER_ID = 1L;
  private static final long PRODUCT_ID = 100L;

  private static DataSource adminDataSource;

  @Autowired private DocumentService service;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean private ProductOwnerReader productOwnerReader;

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    String url = requiredEnvironment("DOC_LOCK_TEST_DB_URL");
    String user = requiredEnvironment("DOC_LOCK_TEST_DB_USER");
    String password = requiredEnvironment("DOC_LOCK_TEST_DB_PASSWORD");
    adminDataSource = new DriverManagerDataSource(url, user, password);
    new JdbcTemplate(adminDataSource).execute("create schema " + SCHEMA);

    String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    DataSource schemaDataSource = new DriverManagerDataSource(schemaUrl, user, password);
    // V2026110202는 CREATE INDEX CONCURRENTLY를 쓰는데 이 설정에서는 Flyway 자신의 연결에 막혀 멈춘다.
    // 문서 테이블은 V2026110201까지 만들어지므로 거기까지만 적용하고, 전체 스키마 검증(validate)은 끈다.
    Flyway.configure()
        .dataSource(schemaDataSource)
        .locations("classpath:db/migration")
        .target("2026110201")
        .load()
        .migrate();

    registry.add("spring.datasource.url", () -> schemaUrl);
    registry.add("spring.datasource.username", () -> user);
    registry.add("spring.datasource.password", () -> password);
  }

  @AfterAll
  static void tearDownDatabase() {
    if (adminDataSource != null) {
      new JdbcTemplate(adminDataSource).execute("drop schema " + SCHEMA + " cascade");
    }
  }

  @BeforeEach
  void resetFixtures() {
    jdbcTemplate.execute(
        "truncate table document_replacements, document_extractions, documents restart identity cascade");
    when(productOwnerReader.lockOwnerId(PRODUCT_ID)).thenReturn(Optional.of(OWNER_ID));
    when(productOwnerReader.findOwnerId(PRODUCT_ID)).thenReturn(Optional.of(OWNER_ID));
  }

  @Test
  void deletesReplacedDocumentTogetherWithItsReplacementRecords() {
    long documentId = insertDocument("documents/100/replaced.png");
    insertReplacement(documentId);

    service.delete(documentId, OWNER_ID);

    assertThat(count("documents", documentId)).isZero();
    assertThat(countReplacements(documentId)).isZero();
  }

  @Test
  void deletesDocumentThatWasNeverReplaced() {
    long documentId = insertDocument("documents/100/plain.png");

    service.delete(documentId, OWNER_ID);

    assertThat(count("documents", documentId)).isZero();
  }

  private long insertDocument(String objectKey) {
    return jdbcTemplate.queryForObject(
        "insert into documents (product_id, uploader_id, object_key, content_type, byte_size,"
            + " mask_confirmed_at) values (?, ?, ?, 'image/png', 10, now()) returning id",
        Long.class,
        PRODUCT_ID,
        OWNER_ID,
        objectKey);
  }

  private void insertReplacement(long documentId) {
    jdbcTemplate.update(
        "insert into document_replacements (document_id, from_version, to_version, replaced_by_id,"
            + " was_protected, old_content_type, new_content_type, old_byte_size, new_byte_size,"
            + " new_sha256, replaced_at) values (?, 1, 2, ?, false, 'image/png', 'image/png', 10,"
            + " 12, ?, now())",
        documentId,
        OWNER_ID,
        "0".repeat(64));
  }

  private int count(String table, long documentId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from " + table + " where id = ?", Integer.class, documentId);
  }

  private int countReplacements(long documentId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from document_replacements where document_id = ?",
        Integer.class,
        documentId);
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
