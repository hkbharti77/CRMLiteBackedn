-- V11037__Enterprise_Journey_Automation_Engine.sql
-- Enterprise Omnichannel Customer Journey & Automation Engine (v4.0 Final Architecture)

-- 1. Journey Transactional Outbox Table
CREATE TABLE IF NOT EXISTS journey_outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID UNIQUE NOT NULL,                          -- Level 1 Event Deduplication Key (Domain Business Level)
    business_id VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,                    -- LEAD, APPOINTMENT, BOOKING, BULK_UPLOAD
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,                        -- EVENT_LEAD_CREATED, EVENT_APPOINTMENT_CREATED
    payload JSONB NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',                    -- PENDING, CLAIMED, PUBLISHED, FAILED
    claimed_at TIMESTAMPTZ,
    claimed_by VARCHAR(100),
    lock_lease_until TIMESTAMPTZ,                            -- Worker Crash Recovery Lease
    retry_count INT DEFAULT 0,
    next_attempt_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_journey_outbox_claim ON journey_outbox_events(next_attempt_at, status) 
WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_journey_outbox_corr ON journey_outbox_events(correlation_id);
CREATE INDEX IF NOT EXISTS idx_journey_outbox_biz ON journey_outbox_events(business_id);

-- 2. Customer Journeys & Immutable Versions
CREATE TABLE IF NOT EXISTS customer_journeys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    trigger_event VARCHAR(100) NOT NULL,
    status VARCHAR(50) DEFAULT 'DRAFT',                       -- DRAFT, VALIDATED, PUBLISHED, PAUSED, ARCHIVED
    reentry_mode VARCHAR(50) DEFAULT 'ONE_ACTIVE_PER_CONTACT',-- ALLOW_MULTIPLE, ONE_ACTIVE_PER_CONTACT, ONCE_EVER, AFTER_COMPLETION
    published_version_id UUID,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS customer_journey_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journey_id UUID NOT NULL REFERENCES customer_journeys(id) ON DELETE CASCADE,
    business_id VARCHAR(50) NOT NULL,
    version_number INT NOT NULL,
    definition_json JSONB NOT NULL,                           -- Normalized compiled graph
    status VARCHAR(50) DEFAULT 'DRAFT',                       -- DRAFT, PUBLISHED, DEPRECATED
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    CONSTRAINT uq_journey_version UNIQUE (journey_id, version_number)
);

-- Immutability Protection Function & Trigger
CREATE OR REPLACE FUNCTION prevent_published_version_update()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' AND NEW.definition_json IS DISTINCT FROM OLD.definition_json THEN
        RAISE EXCEPTION 'Cannot modify definition_json of a PUBLISHED journey version (Version %)', OLD.version_number;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_protect_published_journey_version ON customer_journey_versions;
CREATE TRIGGER trg_protect_published_journey_version
BEFORE UPDATE ON customer_journey_versions
FOR EACH ROW EXECUTE FUNCTION prevent_published_version_update();

ALTER TABLE customer_journeys 
DROP CONSTRAINT IF EXISTS fk_published_version;

ALTER TABLE customer_journeys 
ADD CONSTRAINT fk_published_version 
FOREIGN KEY (published_version_id) REFERENCES customer_journey_versions(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_journeys_biz ON customer_journeys(business_id);

-- 3. Journey Runs & Node Executions
CREATE TABLE IF NOT EXISTS journey_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    journey_id UUID NOT NULL REFERENCES customer_journeys(id) ON DELETE CASCADE,
    journey_version_id UUID NOT NULL REFERENCES customer_journey_versions(id),
    contact_id UUID NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    trigger_event_id UUID NOT NULL,                           -- References journey_outbox_events.event_id for Level 2 Event Dedup
    correlation_id VARCHAR(128) NOT NULL,
    status VARCHAR(50) DEFAULT 'RUNNING',                     -- RUNNING, WAITING, COMPLETED, FAILED, OPTED_OUT, CANCELLED
    context_data JSONB DEFAULT '{}'::jsonb,
    started_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_run_trigger_dedup UNIQUE (journey_id, trigger_event_id, contact_id)
);

CREATE TABLE IF NOT EXISTS journey_node_executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journey_run_id UUID NOT NULL REFERENCES journey_runs(id) ON DELETE CASCADE,
    business_id VARCHAR(50) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    node_type VARCHAR(50) NOT NULL,                           -- ACTION_WHATSAPP, ACTION_EMAIL, ACTION_SMS, WAIT, CONDITION
    status VARCHAR(50) DEFAULT 'PENDING',                      -- PENDING, CLAIMED, RUNNING, WAITING, COMPLETED, FAILED, RETRYING, SKIPPED
    scheduled_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    attempt_count INT DEFAULT 0,
    max_attempts INT DEFAULT 3,
    locked_at TIMESTAMPTZ,
    locked_by VARCHAR(100),
    lock_lease_until TIMESTAMPTZ,                             -- Stale Lease Expiration
    result_data JSONB DEFAULT '{}'::jsonb,
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_journey_runs_biz_contact ON journey_runs(business_id, contact_id);
CREATE INDEX IF NOT EXISTS idx_node_exec_claim ON journey_node_executions(scheduled_at, status) 
WHERE status IN ('PENDING', 'RETRYING', 'WAITING');

-- 4. Logical Message Dispatches & Failover Delivery Attempts
CREATE TABLE IF NOT EXISTS message_dispatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    journey_run_id UUID NOT NULL REFERENCES journey_runs(id) ON DELETE CASCADE,
    node_execution_id UUID UNIQUE NOT NULL REFERENCES journey_node_executions(id) ON DELETE CASCADE,
    operation_id UUID UNIQUE NOT NULL,                        -- Equals node_execution_id for 1:1 loop-compatible stable idempotency
    correlation_id VARCHAR(128) NOT NULL,
    channel VARCHAR(50) NOT NULL,                             -- WHATSAPP, EMAIL, SMS
    status VARCHAR(50) DEFAULT 'QUEUED',                      -- QUEUED, SENT, DELIVERED, FAILED, SKIPPED
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispatch_id UUID NOT NULL REFERENCES message_dispatches(id) ON DELETE CASCADE,
    business_id VARCHAR(50) NOT NULL,
    attempt_no INT NOT NULL,
    provider VARCHAR(50) NOT NULL,                             -- MSG91, TWILIO, FAST2SMS, META_CLOUD, SES, BREVO
    provider_message_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,                              -- SUBMITTED, SENT, DELIVERED, FAILED
    error_code VARCHAR(100),
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_delivery_attempts_disp ON delivery_attempts(dispatch_id);

-- 5. Tri-State Consent & Contact Engagement Facts Projection
CREATE TABLE IF NOT EXISTS contact_channel_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    contact_id UUID NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    email_consent_status VARCHAR(20) DEFAULT 'UNKNOWN',       -- UNKNOWN, OPTED_IN, OPTED_OUT
    whatsapp_consent_status VARCHAR(20) DEFAULT 'UNKNOWN',    -- UNKNOWN, OPTED_IN, OPTED_OUT
    sms_consent_status VARCHAR(20) DEFAULT 'UNKNOWN',         -- UNKNOWN, OPTED_IN, OPTED_OUT
    is_globally_suppressed BOOLEAN DEFAULT FALSE,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_contact_pref UNIQUE (business_id, contact_id)
);

CREATE TABLE IF NOT EXISTS contact_engagement_facts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    contact_id UUID NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    last_email_opened_at TIMESTAMPTZ,
    last_email_clicked_at TIMESTAMPTZ,
    last_whatsapp_reply_at TIMESTAMPTZ,
    last_sms_delivered_at TIMESTAMPTZ,
    message_count INT DEFAULT 0,
    reply_count INT DEFAULT 0,
    facts_json JSONB DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_contact_facts UNIQUE (business_id, contact_id)
);

-- 6. Tenant-Scoped Provider Events (Webhooks) & Bulk Upload Batches
CREATE TABLE IF NOT EXISTS provider_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    correlation_id VARCHAR(128),
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,
    CONSTRAINT uq_provider_event_tenant UNIQUE (business_id, provider, provider_event_id)
);

CREATE TABLE IF NOT EXISTS bulk_upload_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    file_type VARCHAR(20) NOT NULL,
    file_checksum VARCHAR(64) NOT NULL,                        -- SHA-256 File Checksum
    file_size BIGINT NOT NULL,
    uploaded_by VARCHAR(100) NOT NULL,
    column_mapping JSONB NOT NULL,                            -- {"Name": "first_name", "Phone": "phone"}
    status VARCHAR(50) DEFAULT 'QUEUED',                      -- QUEUED, PROCESSING, COMPLETED, PARTIAL_SUCCESS, FAILED
    total_rows INT DEFAULT 0,
    valid_rows INT DEFAULT 0,
    invalid_rows INT DEFAULT 0,
    imported_contacts INT DEFAULT 0,
    updated_contacts INT DEFAULT 0,
    duplicate_rows INT DEFAULT 0,
    failed_rows INT DEFAULT 0,
    auto_trigger_journey_id UUID REFERENCES customer_journeys(id) ON DELETE SET NULL,
    storage_file_path TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_bulk_checksum_tenant UNIQUE (business_id, file_checksum)
);

CREATE TABLE IF NOT EXISTS bulk_upload_row_errors (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES bulk_upload_batches(id) ON DELETE CASCADE,
    business_id VARCHAR(50) NOT NULL,
    row_number INT NOT NULL,
    raw_data JSONB,
    error_code VARCHAR(100) NOT NULL,                         -- INVALID_PHONE, INVALID_EMAIL, DUPLICATE_CONTACT
    error_message TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 7. Audit Ledger with PII Redaction
CREATE TABLE IF NOT EXISTS journey_execution_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    journey_run_id UUID REFERENCES journey_runs(id) ON DELETE CASCADE,
    node_execution_id UUID REFERENCES journey_node_executions(id) ON DELETE SET NULL,
    correlation_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    channel VARCHAR(50),
    provider VARCHAR(50),
    provider_message_id VARCHAR(255),
    template_id VARCHAR(100),
    message_hash VARCHAR(64),
    status VARCHAR(50) NOT NULL,
    request_metadata JSONB,                                   -- PII Redacted Metadata
    response_metadata JSONB,                                  -- PII Redacted Metadata
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_ledger_run ON journey_execution_events(journey_run_id);
CREATE INDEX IF NOT EXISTS idx_audit_ledger_corr ON journey_execution_events(correlation_id);
