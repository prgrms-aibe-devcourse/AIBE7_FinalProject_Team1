-- 배포용 복구: 예약 변경 확인(P3) 테이블.
-- db/pending/trade-p3(V2026102602)에만 있어 정식 경로로 만든 DB에는 없다.
-- product_events(정식 V2026102801)와 purchase_requests(위 V2026110301) 뒤에 실행되므로 FK 순서 문제가 없다.

CREATE TABLE IF NOT EXISTS transaction_change_reviews (
    purchase_request_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    facts_version BIGINT NOT NULL,
    change_facts JSONB NOT NULL,
    decision VARCHAR(20),
    reviewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (purchase_request_id, event_id),
    CONSTRAINT fk_transaction_change_reviews_request
        FOREIGN KEY (purchase_request_id) REFERENCES purchase_requests (id),
    CONSTRAINT fk_transaction_change_reviews_event
        FOREIGN KEY (event_id) REFERENCES product_events (id),
    CONSTRAINT ck_transaction_change_reviews_facts_version CHECK (facts_version > 0),
    CONSTRAINT ck_transaction_change_reviews_facts_object
        CHECK (jsonb_typeof(change_facts) = 'object'),
    CONSTRAINT ck_transaction_change_reviews_decision
        CHECK (decision IS NULL OR decision IN ('KEEP', 'CANCEL')),
    CONSTRAINT ck_transaction_change_reviews_reviewed_pair
        CHECK (
            (decision IS NULL AND reviewed_at IS NULL)
            OR (decision IS NOT NULL AND reviewed_at IS NOT NULL)
        )
);

CREATE INDEX IF NOT EXISTS ix_transaction_change_reviews_unreviewed
    ON transaction_change_reviews (purchase_request_id, event_id)
    WHERE decision IS NULL;
