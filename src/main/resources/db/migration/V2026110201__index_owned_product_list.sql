-- 내 제품 페이지 조회의 소유자 필터와 최신순 정렬을 지원한다.
CREATE INDEX IF NOT EXISTS ix_products_owner_archive_created
    ON products (owner_id, archived_at, created_at DESC, id DESC);
