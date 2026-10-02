-- V2: Store idempotency response JSON, add current_reservation_id to seats, and scope idempotency to show and user

ALTER TABLE idempotency_records
    ADD COLUMN IF NOT EXISTS show_id UUID REFERENCES shows (id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255),
    ADD COLUMN IF NOT EXISTS reservation_id UUID REFERENCES reservations (id) ON DELETE SET NULL,
    ADD COLUMN response_json JSONB;

ALTER TABLE idempotency_records
    ALTER COLUMN request_key DROP NOT NULL,
    ALTER COLUMN status DROP NOT NULL;

ALTER TABLE idempotency_records
    DROP CONSTRAINT IF EXISTS idempotency_records_request_key_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_idempotency_records_show_user_key
    ON idempotency_records (show_id, user_id, idempotency_key);

ALTER TABLE seats
    ADD COLUMN IF NOT EXISTS current_reservation_id UUID REFERENCES reservations (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_seats_current_reservation_id
    ON seats (current_reservation_id);
