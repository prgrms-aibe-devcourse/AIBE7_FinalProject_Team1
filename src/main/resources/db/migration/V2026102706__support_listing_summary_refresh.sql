ALTER TABLE listings ADD COLUMN summary_requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE listings ADD COLUMN summary_available_at TIMESTAMP WITH TIME ZONE;

UPDATE listings
   SET summary_requested_at = summary_updated_at, summary_available_at = summary_updated_at
 WHERE summary_status IS NOT NULL;

-- 갱신 중에도 이전 요약을 보관한다. 공개 여부는 서비스에서 제품 버전과 권한을 다시 검사한다.
ALTER TABLE listings DROP CONSTRAINT ck_listing_summary_state;
ALTER TABLE listings ADD CONSTRAINT ck_listing_summary_state CHECK (
    (summary_status IS NULL AND summary_text IS NULL AND summary_attempt_id IS NULL
        AND summary_source_product_version IS NULL AND summary_source_listing_version IS NULL
        AND summary_error_code IS NULL AND summary_updated_at IS NULL
        AND summary_requested_at IS NULL AND summary_available_at IS NULL)
    OR (summary_status IS NOT NULL AND summary_status IN ('QUEUED','PROCESSING','READY','FAILED')
        AND summary_attempt_id IS NOT NULL AND summary_source_product_version IS NOT NULL
        AND summary_source_listing_version IS NOT NULL AND summary_updated_at IS NOT NULL
        AND (summary_text IS NULL OR CHAR_LENGTH(TRIM(summary_text)) > 0)
        AND ((summary_status IN ('QUEUED','PROCESSING') AND summary_error_code IS NULL)
            OR (summary_status = 'READY' AND summary_text IS NOT NULL AND summary_error_code IS NULL)
            OR (summary_status = 'FAILED' AND summary_error_code IS NOT NULL))));
