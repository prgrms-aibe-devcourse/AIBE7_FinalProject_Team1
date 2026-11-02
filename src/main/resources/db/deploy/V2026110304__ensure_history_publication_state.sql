-- 배포용 복구: 제품 이력 공개·검토 컬럼.
-- 정식 V2026103002와 같은 결과를 보장한다. 공유 DB처럼 그 버전 번호가 다른 SQL로 이미 적용돼 있어 실제 컬럼이 없는 경우를 보완한다.
-- 이미 있으면 모두 건너뛴다.

ALTER TABLE product_events
    ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE product_events
    ADD COLUMN IF NOT EXISTS public_text_reviewed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS public_reason_reviewed BOOLEAN NOT NULL DEFAULT FALSE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'ck_product_events_visibility'
           AND conrelid = 'product_events'::regclass
    ) THEN
        ALTER TABLE product_events ADD CONSTRAINT ck_product_events_visibility
            CHECK (visibility IN ('PRIVATE', 'PUBLIC'));
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS ix_product_events_publication
    ON product_events (product_id, visibility, occurred_on, created_at, id);
