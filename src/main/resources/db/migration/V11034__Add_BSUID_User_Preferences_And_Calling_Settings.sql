-- Flyway Migration: V11034__Add_BSUID_User_Preferences_And_Calling_Settings.sql
-- Implements Priority 2 Meta WhatsApp Business webhooks & BSUID Addressing

-- 1. Contact BSUID & Marketing Opt-Out Fields
ALTER TABLE contacts
ADD COLUMN IF NOT EXISTS bsuid VARCHAR(128),
ADD COLUMN IF NOT EXISTS parent_bsuid VARCHAR(128),
ADD COLUMN IF NOT EXISTS marketing_opted_out BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS marketing_opted_out_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS marketing_opt_out_source VARCHAR(100),
ADD COLUMN IF NOT EXISTS marketing_preference_at TIMESTAMPTZ;

ALTER TABLE contacts
ALTER COLUMN wa_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_contacts_bsuid ON contacts(tenant_id, bsuid);
CREATE INDEX IF NOT EXISTS idx_contacts_marketing_opted_out ON contacts(tenant_id, marketing_opted_out);

-- 2. Campaign Recipient BSUID & Identity Metadata
ALTER TABLE whatsapp_campaign_recipients
ADD COLUMN IF NOT EXISTS bsuid VARCHAR(128),
ADD COLUMN IF NOT EXISTS recipient_identity_type VARCHAR(20) DEFAULT 'PHONE';

ALTER TABLE whatsapp_campaign_recipients
ALTER COLUMN phone_number DROP NOT NULL;

-- 3. Dedicated Phone-Scoped Thread Ownership Table
CREATE TABLE IF NOT EXISTS contact_phone_thread_controls (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    contact_id UUID NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    phone_number_id VARCHAR(100) NOT NULL,
    owner_app_id VARCHAR(100),
    previous_owner_app_id VARCHAR(100),
    thread_control_event VARCHAR(50),
    thread_control_changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    thread_control_source VARCHAR(100),
    CONSTRAINT uq_contact_phone_thread UNIQUE (tenant_id, contact_id, phone_number_id)
);

CREATE INDEX IF NOT EXISTS idx_thread_ctrl_lookup ON contact_phone_thread_controls(tenant_id, contact_id, phone_number_id);

-- 4. Canonical Sorted-JSON SHA-256 Deduplicated User Preferences Ledger
-- user_id is nullable because BSUID is not guaranteed in every interaction (wa_id / phone identity can be present)
CREATE TABLE IF NOT EXISTS whatsapp_user_preferences_ledger (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    waba_id VARCHAR(100),
    phone_number_id VARCHAR(100),
    user_id VARCHAR(128),
    parent_user_id VARCHAR(128),
    wa_id VARCHAR(32),
    category VARCHAR(100) NOT NULL,
    preference_value VARCHAR(50) NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    canonical_fingerprint VARCHAR(128) NOT NULL,
    raw_payload_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_whatsapp_user_preference_event UNIQUE (tenant_id, canonical_fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_wa_pref_lookup ON whatsapp_user_preferences_ledger(tenant_id, user_id, category);
CREATE INDEX IF NOT EXISTS idx_wa_pref_waid ON whatsapp_user_preferences_ledger(tenant_id, wa_id, category);

-- 5. Handover Audit Ledger
CREATE TABLE IF NOT EXISTS whatsapp_handover_audit_ledger (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_number_id VARCHAR(100),
    user_id VARCHAR(128),
    wa_id VARCHAR(32),
    owner_app_id VARCHAR(100),
    previous_owner_app_id VARCHAR(100),
    control_event VARCHAR(100) NOT NULL,
    metadata_json TEXT,
    event_timestamp TIMESTAMPTZ NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_whatsapp_handover_event UNIQUE (tenant_id, fingerprint)
);

-- 6. Phone-Scoped Calling Settings, Business Username & Capability Flags (Scoped to Tenant + WABA + Phone)
CREATE TABLE IF NOT EXISTS whatsapp_phone_number_configs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    waba_id VARCHAR(100),
    phone_number_id VARCHAR(100) NOT NULL,
    display_phone_number VARCHAR(50),
    calling_status VARCHAR(50),
    call_icon_visibility VARCHAR(50),
    callback_permission_status VARCHAR(50),
    sip_status VARCHAR(50),
    srtp_protocol VARCHAR(50),
    calling_settings_updated_at TIMESTAMPTZ,
    business_username VARCHAR(100),
    business_username_status VARCHAR(50),
    business_username_updated_at TIMESTAMPTZ,
    bsuid_enabled BOOLEAN DEFAULT FALSE,
    parent_bsuid_enabled BOOLEAN DEFAULT FALSE,
    handover_feature_enabled BOOLEAN DEFAULT FALSE,
    meta_business_agent_enabled BOOLEAN DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_phone_number_config UNIQUE (tenant_id, waba_id, phone_number_id)
);

CREATE INDEX IF NOT EXISTS idx_phone_cfg_lookup ON whatsapp_phone_number_configs(tenant_id, phone_number_id);
CREATE INDEX IF NOT EXISTS idx_phone_cfg_waba ON whatsapp_phone_number_configs(tenant_id, waba_id, phone_number_id);
