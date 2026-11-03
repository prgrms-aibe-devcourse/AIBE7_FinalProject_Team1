SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE products
    ADD CONSTRAINT fk_products_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    ADD CONSTRAINT ck_products_convergence_required CHECK (owner_id IS NOT NULL AND owner_id > 0 AND serial_number_ciphertext IS NOT NULL);
ALTER TABLE documents
    ADD CONSTRAINT fk_documents_product FOREIGN KEY (product_id) REFERENCES products (id),
    ADD CONSTRAINT fk_documents_uploader FOREIGN KEY (uploader_id) REFERENCES users (id);
ALTER TABLE ownership_transfers
    ADD CONSTRAINT ck_transfers_type CHECK (transfer_type IS NOT NULL AND transfer_type IN ('DIRECT', 'INTERNAL'));

DO $validation$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relnamespace = current_schema()::regnamespace
          AND c.relname = 'ix_products_owner_archive_created'
          AND i.indrelid = 'products'::regclass AND i.indisvalid AND i.indisready
          AND NOT i.indisunique AND i.indpred IS NULL
          AND pg_get_indexdef(i.indexrelid) LIKE '% USING btree (owner_id, archived_at, created_at DESC, id DESC)'
    ) THEN
        RAISE EXCEPTION 'Owned product cursor index has an unexpected definition or validity';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'products'::regclass AND conname = 'ck_products_owner_id_required') THEN
        ALTER TABLE products VALIDATE CONSTRAINT ck_products_owner_id_required;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'products'::regclass AND conname = 'ck_products_serial_ciphertext_required') THEN
        ALTER TABLE products VALIDATE CONSTRAINT ck_products_serial_ciphertext_required;
    ELSIF NOT EXISTS (SELECT 1 FROM pg_attribute
                      WHERE attrelid = 'products'::regclass AND attname = 'serial_number_ciphertext'
                        AND attnotnull AND NOT attisdropped) THEN
        RAISE EXCEPTION 'Serial ciphertext requires a validated CHECK or NOT NULL';
    END IF;
END;
$validation$;
