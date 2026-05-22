-- ============================================================
-- V10014: Create Unified CRM Activity Logs Table
-- Part of the Modular Architecture Refactor (PRD Section 8)
-- ============================================================

CREATE TABLE activity_logs (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID         NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    contact_id   UUID         NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,

    -- Domain reference (which entity triggered this log)
    entity_type  VARCHAR(50)  NOT NULL,     -- 'LEAD' | 'BOOKING' | 'APPOINTMENT' | 'CONTACT'
    entity_id    UUID,                      -- UUID of the related Lead / Booking / Appointment

    -- Activity detail
    activity_type VARCHAR(100) NOT NULL,    -- 'LEAD_CREATED', 'BOOKING_CONFIRMED', etc.
    source        VARCHAR(50)  DEFAULT 'SYSTEM', -- 'FLOW' | 'MANUAL' | 'API' | 'SYSTEM'
    summary       VARCHAR(500),             -- Human-readable description for the timeline UI
    payload       TEXT,                     -- Optional JSON detail for analytics / audit

    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Indexes for performant timeline queries
CREATE INDEX idx_activity_contact_id  ON activity_logs (contact_id);
CREATE INDEX idx_activity_owner_id    ON activity_logs (owner_id);
CREATE INDEX idx_activity_entity      ON activity_logs (entity_type, entity_id);
CREATE INDEX idx_activity_created_at  ON activity_logs (created_at DESC);
CREATE INDEX idx_activity_type        ON activity_logs (activity_type);
