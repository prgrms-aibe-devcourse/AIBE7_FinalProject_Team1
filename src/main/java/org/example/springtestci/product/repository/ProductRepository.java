package org.example.springtestci.product.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.example.springtestci.product.dto.CreateProductRequest;
import org.example.springtestci.product.dto.ProductResponse;
import org.example.springtestci.product.dto.UpdateProductRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** products, laptop_details, product_images와 제품 변경 감사 기록을 관리한다. */
@Repository
public class ProductRepository {

  private static final String PRODUCT_SELECT =
      """
      SELECT p.id, p.title, p.manufacturer, p.model_name, p.purchase_date,
             p.warranty_expires_at, p.warranty_terms, p.facts_version, p.created_at,
             CASE WHEN p.warranty_expires_at IS NULL THEN 'UNKNOWN'
                  WHEN p.warranty_expires_at >= CURRENT_DATE THEN 'ACTIVE'
                  ELSE 'EXPIRED' END AS warranty_status,
             l.cpu_spec, l.gpu_spec, l.ram_gb, l.storage_gb, l.storage_type,
             l.display_spec, l.battery_health_percent, l.os_name
        FROM products p LEFT JOIN laptop_details l ON l.product_id = p.id
      """;

  private final JdbcTemplate jdbc;

  /** 제품 데이터 접근에 사용할 JDBC 템플릿으로 저장소를 구성한다. */
  public ProductRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 정규화된 제조사와 일련번호 지문 조합이 이미 등록됐는지 확인한다. */
  public boolean existsByManufacturerAndSerial(String manufacturerNormalized, String fingerprint) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM products
             WHERE manufacturer_normalized = ? AND serial_number_fingerprint = ?
            """,
            Integer.class,
            manufacturerNormalized,
            fingerprint);
    return count != null && count > 0;
  }

  /** 암호화된 일련번호와 기본 제품 정보를 저장하고 생성된 제품 ID를 반환한다. */
  public long insertProduct(
      long ownerId,
      CreateProductRequest request,
      String manufacturerNormalized,
      String fingerprint,
      String ciphertext) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  """
                  INSERT INTO products (category, manufacturer, manufacturer_normalized,
                      model_name, purchase_date, serial_number_fingerprint, title,
                      warranty_expires_at, warranty_terms, owner_id, serial_number_ciphertext)
                  VALUES ('LAPTOP', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                  """,
                  new String[] {"id"});
          statement.setString(1, request.manufacturer().trim());
          statement.setString(2, manufacturerNormalized);
          statement.setString(3, request.modelName().trim());
          statement.setObject(4, request.purchaseDate());
          statement.setString(5, fingerprint);
          statement.setString(6, request.title().trim());
          statement.setObject(7, request.warrantyExpiresAt());
          statement.setString(8, request.warrantyTerms());
          statement.setLong(9, ownerId);
          statement.setString(10, ciphertext);
          return statement;
        },
        keys);
    return keys.getKey().longValue();
  }

  /** 등록 요청에서 값이 제공된 수정 가능 필드의 최초 작성자를 기록한다. */
  public void insertFieldAuthors(long productId, long ownerId, CreateProductRequest request) {
    insertFieldAuthor(productId, "TITLE", ownerId);
    if (request.purchaseDate() != null) insertFieldAuthor(productId, "PURCHASE_DATE", ownerId);
    if (request.warrantyExpiresAt() != null)
      insertFieldAuthor(productId, "WARRANTY_EXPIRES_AT", ownerId);
    if (request.warrantyTerms() != null && !request.warrantyTerms().isBlank())
      insertFieldAuthor(productId, "WARRANTY_TERMS", ownerId);
  }

  /** 아직 작성자가 없는 제품 필드에 최초 작성자를 기록한다. */
  public void insertFieldAuthor(long productId, String fieldName, long authorId) {
    jdbc.update(
        """
        INSERT INTO product_field_authors (product_id, field_name, author_id)
        SELECT ?, ?, ? WHERE NOT EXISTS (
            SELECT 1 FROM product_field_authors WHERE product_id = ? AND field_name = ?)
        """,
        productId,
        fieldName,
        authorId,
        productId,
        fieldName);
  }

  /** 지정한 제품 필드의 최초 작성자 ID를 조회한다. */
  public Optional<Long> findFieldAuthor(long productId, String fieldName) {
    return jdbc
        .queryForList(
            "SELECT author_id FROM product_field_authors WHERE product_id = ? AND field_name = ?",
            Long.class,
            productId,
            fieldName)
        .stream()
        .findFirst();
  }

  /** 제품에 연결된 노트북 상세 사양을 저장한다. */
  public void insertLaptopDetails(long productId, CreateProductRequest.Specifications specs) {
    jdbc.update(
        """
        INSERT INTO laptop_details (product_id, cpu_spec, ram_gb, storage_gb, storage_type,
            gpu_spec, os_name, display_spec, battery_health_percent)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        productId,
        specs == null ? null : specs.cpu(),
        specs == null ? null : specs.ramGb(),
        specs == null ? null : specs.storageGb(),
        specs == null ? null : specs.storageType(),
        specs == null ? null : specs.gpu(),
        specs == null ? null : specs.os(),
        specs == null ? null : specs.display(),
        specs == null ? null : specs.batteryHealthPercent());
  }

  /** 보관되지 않은 제품을 소유자 범위에서 단건 조회한다. */
  public Optional<ProductResponse> findOwned(long productId, long ownerId) {
    return jdbc
        .query(
            PRODUCT_SELECT + " WHERE p.id = ? AND p.owner_id = ? AND p.archived_at IS NULL",
            this::product,
            productId,
            ownerId)
        .stream()
        .findFirst();
  }

  /** 소유자의 보관되지 않은 제품을 최신 등록 순서로 조회한다. */
  public List<ProductResponse> findAllOwned(long ownerId) {
    return jdbc.query(
        PRODUCT_SELECT
            + " WHERE p.owner_id = ? AND p.archived_at IS NULL"
            + " ORDER BY p.created_at DESC, p.id DESC",
        this::product,
        ownerId);
  }

  /** 소유자 확인을 거쳐 제품의 암호화된 일련번호를 조회한다. */
  public Optional<String> findSerialCiphertextOwned(long productId, long ownerId) {
    return jdbc
        .queryForList(
            """
            SELECT serial_number_ciphertext FROM products
             WHERE id = ? AND owner_id = ? AND archived_at IS NULL
            """,
            String.class,
            productId,
            ownerId)
        .stream()
        .findFirst();
  }

  /** 소유권 확인과 변경의 동시성 제어를 같은 행 잠금으로 수행한다. */
  public boolean lockOwned(long productId, long ownerId) {
    return !jdbc.queryForList(
            "SELECT id FROM products WHERE id = ? AND owner_id = ? AND archived_at IS NULL FOR UPDATE",
            Long.class,
            productId,
            ownerId)
        .isEmpty();
  }

  /** 허용된 제품 기본 정보를 갱신하고 사실 버전을 증가시킨다. */
  public void updateProduct(long productId, UpdateProductRequest request) {
    jdbc.update(
        """
        UPDATE products
           SET title = ?, purchase_date = ?, warranty_expires_at = ?,
               facts_version = facts_version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE id = ?
        """,
        request.title().trim(),
        request.purchaseDate(),
        request.warrantyExpiresAt(),
        productId);
  }

  /** 제품 수정 전후 사실과 변경 사유를 감사 로그에 기록한다. */
  public void insertProductAudit(
      long productId, long actorId, String beforeFacts, String afterFacts, String reason) {
    jdbc.update(
        """
        INSERT INTO audit_logs
            (product_id, actor_id, target_id, before_facts, after_facts, reason, action)
        VALUES (?, ?, ?, ?, ?, ?, 'PRODUCT_INFO_CORRECTED')
        """,
        productId,
        actorId,
        productId,
        beforeFacts,
        afterFacts,
        reason.trim());
  }

  /** 삭제되지 않은 제품 이미지 수를 반환한다. */
  public int countImages(long productId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM product_images WHERE product_id = ? AND deleted_at IS NULL",
            Integer.class,
            productId);
    return count == null ? 0 : count;
  }

  /** 제품 이미지 메타데이터를 다음 정렬 순서에 저장하고 이미지 ID를 반환한다. */
  public long insertImage(long productId, String storageKey, String contentType, long byteSize) {
    KeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(
        connection -> {
          var statement =
              connection.prepareStatement(
                  """
                  INSERT INTO product_images
                      (product_id, storage_key, content_type, byte_size, sort_order, is_primary)
                  VALUES (?, ?, ?, ?,
                      (SELECT COALESCE(MAX(sort_order) + 1, 0) FROM product_images WHERE product_id = ?),
                      CASE WHEN EXISTS (SELECT 1 FROM product_images
                                        WHERE product_id = ? AND deleted_at IS NULL)
                           THEN FALSE ELSE TRUE END)
                  """,
                  new String[] {"id"});
          statement.setLong(1, productId);
          statement.setString(2, storageKey);
          statement.setString(3, contentType);
          statement.setLong(4, byteSize);
          statement.setLong(5, productId);
          statement.setLong(6, productId);
          return statement;
        },
        keys);
    return keys.getKey().longValue();
  }

  /** 삭제되지 않은 제품 이미지를 표시 순서대로 조회한다. */
  public List<ProductImageRecord> findImages(long productId) {
    return jdbc.query(
        """
        SELECT id, storage_key, content_type, byte_size, sort_order, is_primary
          FROM product_images
         WHERE product_id = ? AND deleted_at IS NULL
         ORDER BY sort_order, id
        """,
        (row, index) ->
            new ProductImageRecord(
                row.getLong("id"),
                row.getString("storage_key"),
                row.getString("content_type"),
                row.getLong("byte_size"),
                row.getInt("sort_order"),
                row.getBoolean("is_primary")),
        productId);
  }

  /** 제품의 삭제되지 않은 이미지 한 장을 조회한다. */
  public Optional<ProductImageRecord> findImage(long productId, long imageId) {
    return findImages(productId).stream().filter(image -> image.imageId() == imageId).findFirst();
  }

  /** 이미지 정렬 순서와 대표 이미지 지정을 원자적으로 교체한다. */
  public void replaceImageOrder(long productId, List<Long> imageIds, long primaryImageId) {
    jdbc.update(
        "UPDATE product_images SET sort_order = sort_order + 1000, is_primary = FALSE"
            + " WHERE product_id = ? AND deleted_at IS NULL",
        productId);
    for (int index = 0; index < imageIds.size(); index++) {
      jdbc.update(
          "UPDATE product_images SET sort_order = ?, is_primary = ? WHERE id = ? AND product_id = ?",
          index,
          imageIds.get(index) == primaryImageId,
          imageIds.get(index),
          productId);
    }
  }

  /** 이미지 참조를 정리하고 이미지를 소프트 삭제한 뒤 남은 순서를 정규화한다. */
  public void softDeleteImage(long productId, long imageId) {
    jdbc.update("DELETE FROM listing_draft_images WHERE image_id = ?", imageId);
    jdbc.update(
        """
        UPDATE product_images
           SET deleted_at = CURRENT_TIMESTAMP, is_primary = FALSE, sort_order = 1000000 + id
         WHERE id = ? AND product_id = ? AND deleted_at IS NULL
        """,
        imageId,
        productId);
    List<ProductImageRecord> remaining = findImages(productId);
    if (!remaining.isEmpty()) {
      long primary =
          remaining.stream()
              .filter(ProductImageRecord::primary)
              .map(ProductImageRecord::imageId)
              .findFirst()
              .orElse(remaining.get(0).imageId());
      replaceImageOrder(
          productId, remaining.stream().map(ProductImageRecord::imageId).toList(), primary);
    }
  }

  /** JDBC 결과 행을 제품 응답 모델로 변환한다. */
  private ProductResponse product(ResultSet row, int index) throws SQLException {
    return new ProductResponse(
        row.getLong("id"),
        row.getString("title"),
        row.getString("manufacturer"),
        row.getString("model_name"),
        row.getObject("purchase_date", java.time.LocalDate.class),
        row.getObject("warranty_expires_at", java.time.LocalDate.class),
        row.getString("warranty_terms"),
        row.getLong("facts_version"),
        null,
        row.getString("warranty_status"),
        new ProductResponse.Specifications(
            row.getString("cpu_spec"),
            row.getString("gpu_spec"),
            row.getObject("ram_gb", Integer.class),
            row.getObject("storage_gb", Integer.class),
            row.getString("storage_type"),
            row.getString("display_spec"),
            row.getObject("battery_health_percent", Integer.class),
            row.getString("os_name")),
        List.of(),
        row.getObject("created_at", java.time.OffsetDateTime.class));
  }

  /** 저장소 내부에서 사용하는 제품 이미지 메타데이터를 나타낸다. */
  public record ProductImageRecord(
      long imageId,
      String storageKey,
      String contentType,
      long byteSize,
      int sortOrder,
      boolean primary) {}
}
