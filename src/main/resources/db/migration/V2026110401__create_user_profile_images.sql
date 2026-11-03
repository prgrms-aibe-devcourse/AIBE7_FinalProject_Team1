-- 회원 프로필 사진. 서버가 256px 정사각 JPEG로 줄인 작은 이미지를 DB에 직접 보관한다.
-- users 행과 분리해 회원 조회가 이미지 바이트를 읽지 않게 하고, 회원이 삭제되면 함께 지운다.
CREATE TABLE user_profile_images (
    user_id BIGINT PRIMARY KEY,
    content BYTEA NOT NULL,
    content_type VARCHAR(30) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_user_profile_images_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
