-- ============================================================
-- V10015: Create Processed Messages Table (Idempotency Layer)
-- Part of the Modular Architecture Refactor (PRD Section 12)
-- ============================================================
-- Purpose: Prevent duplicate Lead / Booking / Appointment records
-- from WhatsApp's at-least-once webhook delivery.
-- Records older than 30 days are purged by the IdempotencyService scheduler.
-- ============================================================

CREATE TABLE processed_messages (
    id           BIGSERIAL    PRIMARY KEY,
    owner_id     UUID         REFERENCES app_users(id) ON DELETE CASCADE,
    message_id   VARCHAR(255) NOT NULL UNIQUE,     -- WhatsApp wamid
    processed_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_msg_owner        ON processed_messages (owner_id);
CREATE INDEX idx_processed_msg_processed_at ON processed_messages (processed_at);
