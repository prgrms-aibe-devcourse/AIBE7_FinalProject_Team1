DO $alignment$
DECLARE
    purchase RECORD;
    summary RECORD;
BEGIN
    IF current_setting('pastport.expected_database', TRUE) IS DISTINCT FROM current_database()
       OR current_setting('pastport.expected_schema', TRUE) IS DISTINCT FROM current_schema() THEN
        RAISE EXCEPTION 'Expected database/schema must match the current connection';
    END IF;
    LOCK TABLE flyway_schema_history IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success)
       OR EXISTS (SELECT version FROM flyway_schema_history GROUP BY version HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Failed or duplicate history; no normalization allowed';
    END IF;
    SELECT * INTO purchase FROM flyway_schema_history WHERE version = '2026102701';
    SELECT * INTO summary FROM flyway_schema_history WHERE version = '2026102706';
    IF summary.installed_rank IS NULL THEN
        IF purchase.type = 'SQL' AND purchase.description = 'support listing summary refresh'
           AND purchase.script = 'V2026102701__support_listing_summary_refresh.sql'
           AND purchase.checksum = 620449880 AND purchase.success
           AND EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '2026102103'
                       AND type = 'SQL' AND description = 'create purchase requests'
                       AND script = 'V2026102103__create_purchase_requests.sql'
                       AND checksum = -778179566 AND success) THEN
            RETURN;
        END IF;
        RAISE EXCEPTION 'Unknown or partial purchase-summary lineage';
    END IF;
    IF purchase.installed_rank IS NULL OR purchase.type IS DISTINCT FROM 'SQL'
       OR purchase.description IS DISTINCT FROM 'create purchase requests'
       OR purchase.script IS DISTINCT FROM 'V2026102701__create_purchase_requests.sql'
       OR purchase.checksum IS DISTINCT FROM -778179566 OR purchase.success IS DISTINCT FROM TRUE
       OR summary.type IS DISTINCT FROM 'SQL'
       OR summary.description IS DISTINCT FROM 'support listing summary refresh'
       OR summary.script IS DISTINCT FROM 'V2026102706__support_listing_summary_refresh.sql'
       OR summary.checksum IS DISTINCT FROM 620449880 OR summary.success IS DISTINCT FROM TRUE
       OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '2026102103') THEN
        RAISE EXCEPTION 'Purchase-summary identity/checksum/target collision';
    END IF;
    IF to_regclass('purchase_requests') IS NULL
       OR (SELECT count(*) FROM information_schema.columns
           WHERE table_schema = current_schema() AND table_name = 'purchase_requests'
             AND column_name IN ('id','room_id','listing_id','request_status','transaction_status',
               'seller_confirmed_version','buyer_confirmed_version','cancelled_by_id','cancel_reason','cancelled_at')) <> 10
       OR (SELECT count(*) FROM pg_constraint WHERE conrelid = 'purchase_requests'::regclass
           AND conname IN ('fk_purchase_requests_room','fk_purchase_requests_listing',
             'fk_purchase_requests_cancelled_by','ck_purchase_requests_request_status',
             'ck_purchase_requests_transaction_status','ck_purchase_requests_status_pair') AND convalidated) <> 6
       OR (SELECT count(*) FROM information_schema.columns
           WHERE table_schema = current_schema() AND table_name = 'listings'
             AND column_name IN ('summary_requested_at','summary_available_at')
             AND data_type = 'timestamp with time zone') <> 2
       OR NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'listings'::regclass
                      AND conname = 'ck_listing_summary_state' AND convalidated
                      AND pg_get_constraintdef(oid) LIKE '%summary_requested_at%'
                      AND pg_get_constraintdef(oid) LIKE '%summary_available_at%') THEN
        RAISE EXCEPTION 'Purchase-summary catalog prerequisites missing';
    END IF;
    UPDATE flyway_schema_history SET version = '2026102103',
        script = 'V2026102103__create_purchase_requests.sql' WHERE installed_rank = purchase.installed_rank;
    UPDATE flyway_schema_history SET version = '2026102701',
        script = 'V2026102701__support_listing_summary_refresh.sql' WHERE installed_rank = summary.installed_rank;
END;
$alignment$;
