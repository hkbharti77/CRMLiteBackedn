-- V11031: Add Template Quality, Account Health, Granular Capabilities, and Outbox Tables

-- 1. Template Health, Quality, Category & Segregated State Timestamps
ALTER TABLE whatsapp_templates
ADD COLUMN IF NOT EXISTS quality_rating VARCHAR(20) DEFAULT 'UNKNOWN',
ADD COLUMN IF NOT EXISTS quality_updated_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS detected_correct_category VARCHAR(50),
ADD COLUMN IF NOT EXISTS category_correction_status VARCHAR(50),
ADD COLUMN IF NOT EXISTS category_correction_detected_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS category_correction_reason VARCHAR(255),
ADD COLUMN IF NOT EXISTS category_change_effective_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS category_previous_value VARCHAR(50),
ADD COLUMN IF NOT EXISTS category_change_reason VARCHAR(255),
-- Segregated per-family event timestamps
ADD COLUMN IF NOT EXISTS last_status_event_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS last_quality_event_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS last_category_event_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS last_component_event_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS last_component_sync_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS last_component_sync_status VARCHAR(50),
ADD COLUMN IF NOT EXISTS last_component_sync_error TEXT;

CREATE INDEX IF NOT EXISTS idx_whatsapp_templates_meta_id ON whatsapp_templates(meta_template_id);
CREATE INDEX IF NOT EXISTS idx_whatsapp_templates_quality ON whatsapp_templates(quality_rating);

-- 2. Account Health, Restrictions & Segregated Domain Timestamps
ALTER TABLE whatsapp_configs
ADD COLUMN IF NOT EXISTS account_status_reason VARCHAR(500),
ADD COLUMN IF NOT EXISTS account_status_updated_at TIMESTAMPTZ,
-- Segregated account domain event timestamps
ADD COLUMN IF NOT EXISTS last_restriction_event_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS last_capability_event_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS last_lifecycle_event_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS messaging_limit_value BIGINT,
ADD COLUMN IF NOT EXISTS messaging_limit_type VARCHAR(50),
ADD COLUMN IF NOT EXISTS messaging_limit_raw VARCHAR(50),
ADD COLUMN IF NOT EXISTS max_phones_per_business_portfolio INTEGER,
ADD COLUMN IF NOT EXISTS max_phones_per_waba INTEGER,
ADD COLUMN IF NOT EXISTS capability_updated_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS restriction_json JSONB,
ADD COLUMN IF NOT EXISTS capability_json JSONB,
ADD COLUMN IF NOT EXISTS violation_json JSONB,
ADD COLUMN IF NOT EXISTS ban_info_json JSONB;

-- 3. Campaign Idempotent Pause Fields
ALTER TABLE whatsapp_campaigns
ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(255),
ADD COLUMN IF NOT EXISTS paused_at TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS paused_by VARCHAR(50),
ADD COLUMN IF NOT EXISTS pause_source_event_id VARCHAR(255);

-- 4. Security & Campaign Outbox Tables with Database-Level Uniqueness Constraints
CREATE TABLE IF NOT EXISTS security_notification_outbox (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    event_type VARCHAR(100) NOT NULL,
    phone_number_id VARCHAR(100),
    meta_user_id VARCHAR(100),
    source_event_id VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT uq_security_notification_event UNIQUE (tenant_id, event_type, phone_number_id, source_event_id)
);

CREATE INDEX IF NOT EXISTS idx_security_outbox_status ON security_notification_outbox(status);

CREATE TABLE IF NOT EXISTS campaign_pause_outbox (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    template_id UUID NOT NULL REFERENCES whatsapp_templates(id) ON DELETE CASCADE,
    source_event_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ,
    CONSTRAINT uq_campaign_pause_event UNIQUE (tenant_id, template_id, source_event_id)
);

CREATE INDEX IF NOT EXISTS idx_campaign_pause_outbox_status ON campaign_pause_outbox(status);
