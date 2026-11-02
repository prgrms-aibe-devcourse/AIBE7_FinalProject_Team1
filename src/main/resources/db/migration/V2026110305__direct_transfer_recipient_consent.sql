ALTER TABLE ownership_transfers ADD COLUMN recipient_accepted_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE ownership_transfers ADD CONSTRAINT chk_direct_recipient_consent
    CHECK (recipient_accepted_at IS NULL OR (transfer_type = 'DIRECT' AND to_user_id IS NOT NULL));
