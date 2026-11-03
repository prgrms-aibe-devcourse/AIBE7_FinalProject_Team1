DO $catalog$
DECLARE
    expected RECORD;
BEGIN
    IF current_setting('server_version_num')::integer / 10000 <> 17 THEN
        RAISE EXCEPTION 'Catalog normalization contract is verified on PostgreSQL 17 only';
    END IF;
    FOR expected IN SELECT * FROM (VALUES
        ('ownership_transfers', 'ck_transfers_pending_slot', '36ac565a08355290e805af68b361b71f'),
        ('ownership_transfers', 'ck_transfers_status', '7f696b854dc00c2004b22962b316cfa1'),
        ('ownership_transfers', 'fk_transfers_from_user', '7586a7df98fc6aae59855f5825892513'),
        ('ownership_transfers', 'fk_transfers_product', '4b3b82bfa0607d8da9f5258ee162fe35'),
        ('ownership_transfers', 'fk_transfers_to_user', '3a31cbd08a9756fee2173a8194253b97'),
        ('ownership_transfers', 'uk_transfers_pending_product', '8cdbb14a55885e1edabab62a2e78ff67'),
        ('ownership_transfers', 'uk_transfers_token_hash', '0f830df04bb663b6de1300c0f33de671'),
        ('purchase_requests', 'ck_purchase_requests_request_status', '0694ab21f260604c10031dad18a11f7d'),
        ('purchase_requests', 'ck_purchase_requests_status_pair', '01e6c99a4273b4e232e086c9024fb4c8'),
        ('purchase_requests', 'ck_purchase_requests_transaction_status', 'aead20e169c0ab4e73b79b38575b99c7'),
        ('purchase_requests', 'fk_purchase_requests_cancelled_by', 'ffcbed73e8979aaa3431e0febd298746'),
        ('purchase_requests', 'fk_purchase_requests_listing', 'f15c16bbd4885144e918871952178667'),
        ('purchase_requests', 'fk_purchase_requests_room', '1088104861d5a160f5ef942e8cca3fe5'),
        ('listings', 'ck_listing_summary_state', '8c0a901f60234cd9e9cad10b2fb0f396')
    ) AS spec(table_name, constraint_name, definition_md5)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c
            WHERE c.conrelid = to_regclass(format('%I.%I', current_schema(), expected.table_name))
              AND c.conname = expected.constraint_name AND c.convalidated
              AND md5(pg_get_constraintdef(c.oid)) = expected.definition_md5
        ) THEN
            RAISE EXCEPTION 'Unexpected or missing catalog definition: %.%', expected.table_name, expected.constraint_name;
        END IF;
    END LOOP;
END;
$catalog$;
