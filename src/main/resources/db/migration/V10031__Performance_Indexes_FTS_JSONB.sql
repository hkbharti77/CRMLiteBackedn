-- ============================================================
-- V10031: Performance — Missing Indexes, FTS, and JSONB columns
-- Covers AP-5, AP-7, AP-8, AP-9, AP-10
-- ============================================================

-- ── AP-10: Missing composite indexes ─────────────────────────────────────

-- leads: dashboard pipeline view (status filter + soft-delete)
CREATE INDEX IF NOT EXISTS idx_leads_owner_status_deleted
    ON leads (owner_id, status, deleted);

-- leads: contact lead history (most recent first)
CREATE INDEX IF NOT EXISTS idx_leads_contact_created
    ON leads (contact_id, created_at DESC);

-- leads: last activity sort (most common sort on dashboard)
CREATE INDEX IF NOT EXISTS idx_leads_owner_last_activity
    ON leads (owner_id, last_activity DESC);

-- tickets: paginated dashboard with soft-delete
CREATE INDEX IF NOT EXISTS idx_tickets_owner_status_deleted_created
    ON tickets (owner_id, status, deleted, created_at DESC);

-- tickets: SLA breach scan (scheduled job runs this frequently)
CREATE INDEX IF NOT EXISTS idx_tickets_sla_scan
    ON tickets (owner_id, sla_breached, deleted, first_response_due_at, resolution_due_at)
    WHERE deleted = false AND sla_breached = false;

-- chat_messages: conversation load (contact + time sort)
CREATE INDEX IF NOT EXISTS idx_chat_contact_timestamp
    ON chat_messages (contact_id, timestamp DESC);

-- user_sessions: auth filter check on every request
CREATE INDEX IF NOT EXISTS idx_sessions_user_active
    ON user_sessions (user_id, status, expires_at);

-- processed_messages: webhook deduplication lookup
CREATE INDEX IF NOT EXISTS idx_processed_wa_message_id
    ON processed_messages (message_id);

-- conversation_states: flow engine lookup (already unique but confirm index exists)
CREATE INDEX IF NOT EXISTS idx_conv_state_contact
    ON conversation_states (contact_id);

-- appointments: owner dashboard + date range queries
CREATE INDEX IF NOT EXISTS idx_appt_owner_datetime
    ON appointments (owner_id, appointment_date_time DESC);

-- ── AP-8: Full-Text Search on tickets ────────────────────────────────────

-- Add a generated tsvector column for fast full-text search
-- This eliminates the slow LIKE '%term%' sequential scan on TEXT columns
ALTER TABLE tickets
    ADD COLUMN IF NOT EXISTS search_vector TSVECTOR
    GENERATED ALWAYS AS (
        to_tsvector('english',
            COALESCE(subject,         '') || ' ' ||
            COALESCE(submitter_name,  '') || ' ' ||
            COALESCE(submitter_email, '') || ' ' ||
            COALESCE(category,        '')
        )
    ) STORED;

-- GIN index for fast FTS queries using @@ operator
CREATE INDEX IF NOT EXISTS idx_tickets_search_vector
    ON tickets USING GIN (search_vector);

-- ── AP-9: Convert TEXT → JSONB for queryable JSON columns ────────────────

-- conversation_states.collected_data: was TEXT, now JSONB for JSON path queries
ALTER TABLE conversation_states ALTER COLUMN collected_data DROP DEFAULT;

ALTER TABLE conversation_states
    ALTER COLUMN collected_data TYPE JSONB
    USING CASE
        WHEN collected_data IS NULL OR collected_data = '' THEN '{}'::jsonb
        ELSE collected_data::jsonb
    END;

ALTER TABLE conversation_states
    ALTER COLUMN collected_data SET DEFAULT '{}';

-- GIN index on collected_data for JSON path operators
CREATE INDEX IF NOT EXISTS idx_conv_collected_data
    ON conversation_states USING GIN (collected_data);

-- appointments.collected_data: also TEXT, convert to JSONB
ALTER TABLE appointments ALTER COLUMN collected_data DROP DEFAULT;

ALTER TABLE appointments
    ALTER COLUMN collected_data TYPE JSONB
    USING CASE
        WHEN collected_data IS NULL OR collected_data = '' THEN '{}'::jsonb
        ELSE collected_data::jsonb
    END;

ALTER TABLE appointments
    ALTER COLUMN collected_data SET DEFAULT '{}';

-- ── AP-5: WhatsApp JSON blobs → JSONB ────────────────────────────────────

ALTER TABLE whatsapp_configs ALTER COLUMN interactive_menu_json DROP DEFAULT;
ALTER TABLE whatsapp_configs ALTER COLUMN custom_sub_menus_json DROP DEFAULT;
ALTER TABLE whatsapp_configs ALTER COLUMN custom_messages_json DROP DEFAULT;

ALTER TABLE whatsapp_configs
    ALTER COLUMN interactive_menu_json TYPE JSONB
    USING CASE
        WHEN interactive_menu_json IS NULL OR interactive_menu_json = '' THEN NULL
        ELSE interactive_menu_json::jsonb
    END;

ALTER TABLE whatsapp_configs
    ALTER COLUMN custom_sub_menus_json TYPE JSONB
    USING CASE
        WHEN custom_sub_menus_json IS NULL OR custom_sub_menus_json = '' THEN NULL
        ELSE custom_sub_menus_json::jsonb
    END;

ALTER TABLE whatsapp_configs
    ALTER COLUMN custom_messages_json TYPE JSONB
    USING CASE
        WHEN custom_messages_json IS NULL OR custom_messages_json = '' THEN NULL
        ELSE custom_messages_json::jsonb
    END;

-- GIN index on interactive menu (most frequently queried JSON blob)
CREATE INDEX IF NOT EXISTS idx_wa_interactive_menu
    ON whatsapp_configs USING GIN (interactive_menu_json);

-- ── Ticket number generation: replace COUNT(*) with sequence ─────────────
-- COUNT(*) to generate ticket numbers is O(n) — use a sequence instead
CREATE SEQUENCE IF NOT EXISTS ticket_number_seq START 1000;

COMMENT ON SEQUENCE ticket_number_seq
    IS 'Used for generating unique ticket numbers. Replaces the COUNT(*) approach in TicketNumberGenerator.';
