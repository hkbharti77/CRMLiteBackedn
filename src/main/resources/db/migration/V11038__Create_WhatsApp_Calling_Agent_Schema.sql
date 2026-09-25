-- Flyway Migration: V11038__Create_WhatsApp_Calling_Agent_Schema.sql
-- Implements Meta-Compliant WhatsApp Calling Agent database tables & schemas

-- 1. Call Limit Events Table (Event-Sourced Rate Limit Ledger)
CREATE TABLE IF NOT EXISTS call_limit_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_number_id VARCHAR(64) NOT NULL,
    user_wa_id VARCHAR(32),
    event_type VARCHAR(64) NOT NULL, -- PERMISSION_REQUEST, CALL_INITIATED, CALL_CONNECTED, CALL_UNANSWERED, CALL_REJECTED
    call_id VARCHAR(128),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_call_limit_events_lookup ON call_limit_events(tenant_id, phone_number_id, user_wa_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_call_limit_events_tenant_time ON call_limit_events(tenant_id, phone_number_id, occurred_at);

-- 2. WhatsApp Call Permissions Table
CREATE TABLE IF NOT EXISTS whatsapp_call_permissions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_number_id VARCHAR(64) NOT NULL,
    user_wa_id VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL, -- PENDING, GRANTED_TEMPORARY, GRANTED_PERMANENT, DENIED, REVOKED, EXPIRED
    permission_type VARCHAR(32) NOT NULL DEFAULT 'TEMPORARY', -- TEMPORARY, PERMANENT
    is_permanent BOOLEAN DEFAULT FALSE,
    response_source VARCHAR(32), -- USER_RESPONSE, AUTO_CALLBACK, AUTO_REVOCATION
    granted_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    last_permission_webhook_at TIMESTAMPTZ,
    source VARCHAR(64),
    last_permission_request_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wa_call_permission UNIQUE (tenant_id, phone_number_id, user_wa_id)
);

CREATE INDEX IF NOT EXISTS idx_wa_call_perm_tenant_user ON whatsapp_call_permissions(tenant_id, user_wa_id, status);
CREATE INDEX IF NOT EXISTS idx_wa_call_perm_phone_status ON whatsapp_call_permissions(tenant_id, phone_number_id, status);

-- 3. WhatsApp Call Sessions Table
CREATE TABLE IF NOT EXISTS whatsapp_call_sessions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    waba_id VARCHAR(64) NOT NULL,
    phone_number_id VARCHAR(64) NOT NULL,
    call_id VARCHAR(128) NOT NULL UNIQUE,
    direction VARCHAR(32) NOT NULL, -- USER_INITIATED, BUSINESS_INITIATED
    from_wa_id VARCHAR(32) NOT NULL,
    to_wa_id VARCHAR(32) NOT NULL,
    signaling_mode VARCHAR(16) NOT NULL DEFAULT 'GRAPH', -- GRAPH, SIP
    media_mode VARCHAR(16) NOT NULL DEFAULT 'WEBRTC', -- WEBRTC, SIP_TLS
    state VARCHAR(32) NOT NULL,
    permission_state VARCHAR(32),
    sdp_offer_hash VARCHAR(64),
    sdp_answer_hash VARCHAR(64),
    remote_fingerprint VARCHAR(128),
    ice_session_id VARCHAR(128),
    codec VARCHAR(16) DEFAULT 'Opus',
    started_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    connected_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    duration_seconds INT DEFAULT 0,
    termination_source VARCHAR(32), -- USER, AGENT, SYSTEM, ERROR
    termination_reason VARCHAR(64),
    meta_error_code INT,
    meta_error_title VARCHAR(128),
    meta_error_message TEXT,
    meta_request_id VARCHAR(128),
    failure_stage VARCHAR(64),
    ai_session_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wa_call_sessions_tenant_state ON whatsapp_call_sessions(tenant_id, state);
CREATE INDEX IF NOT EXISTS idx_wa_call_sessions_call_id ON whatsapp_call_sessions(tenant_id, call_id);
CREATE INDEX IF NOT EXISTS idx_wa_call_sessions_phone ON whatsapp_call_sessions(tenant_id, phone_number_id, created_at);

-- 4. Extend WhatsApp Phone Number Configs for Signaling Mode
ALTER TABLE whatsapp_phone_number_configs
ADD COLUMN IF NOT EXISTS waba_id VARCHAR(100),
ADD COLUMN IF NOT EXISTS signaling_mode VARCHAR(32) DEFAULT 'GRAPH',
ADD COLUMN IF NOT EXISTS max_concurrent_calls INT DEFAULT 5;

