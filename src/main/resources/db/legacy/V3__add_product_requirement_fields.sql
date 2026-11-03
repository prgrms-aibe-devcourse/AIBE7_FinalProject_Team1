ALTER TABLE electronic_products
    ADD COLUMN product_name VARCHAR(150),
    ADD COLUMN warranty_expires_at DATE;

UPDATE electronic_products
SET product_name = model_name
WHERE product_name IS NULL;

ALTER TABLE electronic_products
    ALTER COLUMN product_name SET NOT NULL;

ALTER TABLE laptop_details
    ADD COLUMN battery_status VARCHAR(100);
