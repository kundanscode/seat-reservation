-- V3: Add cancelled_at timestamp to reservations table

ALTER TABLE reservations
    ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP WITH TIME ZONE;
