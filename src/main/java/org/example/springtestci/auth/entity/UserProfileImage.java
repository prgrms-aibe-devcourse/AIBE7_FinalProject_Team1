package org.example.springtestci.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 회원 한 명의 프로필 사진. 회원 id가 곧 기본 키라 회원당 한 장만 보관한다. */
@Entity
@Table(name = "user_profile_images")
public class UserProfileImage {

  @Id
  @Column(name = "user_id")
  private Long userId;

  @JdbcTypeCode(SqlTypes.VARBINARY)
  @Column(nullable = false)
  private byte[] content;

  @Column(name = "content_type", nullable = false, length = 30)
  private String contentType;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** JPA가 사용하는 기본 생성자다. */
  protected UserProfileImage() {}

  /** 회원의 사진을 새로 만든다. */
  public UserProfileImage(long userId, byte[] content, String contentType, Instant now) {
    this.userId = userId;
    replace(content, contentType, now);
  }

  /** 이미지를 새 것으로 바꾼다. */
  public void replace(byte[] content, String contentType, Instant now) {
    this.content = content;
    this.contentType = contentType;
    this.updatedAt = now;
  }

  /** 저장된 이미지 바이트를 돌려준다. */
  public byte[] getContent() {
    return content;
  }

  /** 이미지 MIME 타입을 돌려준다. */
  public String getContentType() {
    return contentType;
  }

  /** 마지막으로 바꾼 시각을 돌려준다. */
  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
