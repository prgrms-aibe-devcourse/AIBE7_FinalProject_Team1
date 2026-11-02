-- 배포용 복구: 구매 요청 취소 메타데이터 제약.
-- db/pending/trade-p3(V2026102602)의 첫 문장과 같은 제약이며, 이미 있으면 건너뛴다.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'ck_purchase_requests_cancellation_metadata'
           AND conrelid = 'purchase_requests'::regclass
    ) THEN
        ALTER TABLE purchase_requests
            ADD CONSTRAINT ck_purchase_requests_cancellation_metadata
            CHECK (
                (
                    request_status = 'CANCELLED'
                    AND transaction_status IS NOT DISTINCT FROM 'CANCELLED'
                    AND cancelled_by_id IS NOT NULL
                    AND cancel_reason IS NOT NULL
                    AND cancel_reason IN (
                        'SELF_CIRCUMSTANCES',
                        'MUTUAL_AGREEMENT',
                        'COUNTERPART_UNREACHABLE',
                        'CONDITION_MISMATCH'
                    )
                    AND cancelled_at IS NOT NULL
                )
                OR
                (
                    (request_status <> 'CANCELLED' OR transaction_status IS DISTINCT FROM 'CANCELLED')
                    AND cancelled_by_id IS NULL
                    AND cancel_reason IS NULL
                    AND cancelled_at IS NULL
                )
            );
    END IF;
END
$$;
