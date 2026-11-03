ALTER TABLE electronic_products RENAME TO products;

ALTER TABLE products RENAME COLUMN product_type TO category;
ALTER TABLE products RENAME COLUMN product_name TO title;
ALTER TABLE products RENAME COLUMN purchased_at TO purchase_date;
ALTER TABLE products RENAME COLUMN serial_fingerprint TO serial_number_fingerprint;

ALTER TABLE products
    ALTER COLUMN title TYPE VARCHAR(200),
    ALTER COLUMN model_name TYPE VARCHAR(100),
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN owner_id BIGINT,
    ADD COLUMN serial_number_ciphertext VARCHAR(512),
    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE products DROP COLUMN serial_last4;

ALTER TABLE products
    RENAME CONSTRAINT uk_electronic_products_serial_fingerprint
    TO uk_products_serial_number_fingerprint;
ALTER TABLE products
    RENAME CONSTRAINT ck_electronic_products_product_type
    TO ck_products_category;

ALTER TABLE products
    ADD CONSTRAINT ck_products_owner_id_required
        CHECK (owner_id IS NOT NULL AND owner_id > 0) NOT VALID,
    ADD CONSTRAINT ck_products_serial_ciphertext_required
        CHECK (serial_number_ciphertext IS NOT NULL) NOT VALID;

CREATE INDEX ix_products_owner_id_active
    ON products (owner_id)
    WHERE archived_at IS NULL;

ALTER TABLE laptop_details RENAME COLUMN cpu TO cpu_spec;
ALTER TABLE laptop_details RENAME COLUMN memory_gb TO ram_gb;
ALTER TABLE laptop_details RENAME COLUMN gpu TO gpu_spec;
ALTER TABLE laptop_details RENAME COLUMN operating_system TO os_name;

ALTER TABLE laptop_details
    ADD COLUMN display_spec VARCHAR(100),
    ADD COLUMN battery_health_percent SMALLINT,
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

UPDATE laptop_details
SET display_spec = CAST(display_size_inches AS VARCHAR) || '"'
WHERE display_size_inches IS NOT NULL;

UPDATE laptop_details
SET storage_type = NULL
WHERE storage_type NOT IN ('SSD', 'HDD', 'MIXED');

ALTER TABLE laptop_details
    DROP COLUMN display_size_inches,
    DROP COLUMN battery_status,
    ALTER COLUMN storage_type TYPE VARCHAR(20),
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE laptop_details
    DROP CONSTRAINT ck_laptop_details_storage_type;
ALTER TABLE laptop_details
    ADD CONSTRAINT ck_laptop_details_storage_type CHECK (
        storage_type IS NULL OR storage_type IN ('SSD', 'HDD', 'MIXED')
    ),
    ADD CONSTRAINT ck_laptop_details_battery_health CHECK (
        battery_health_percent IS NULL
        OR battery_health_percent BETWEEN 0 AND 100
    );
