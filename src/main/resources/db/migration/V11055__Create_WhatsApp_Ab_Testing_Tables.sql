-- Migration V11055: Create WhatsApp Campaign Variants and A/B Testing Infrastructure
-- Adds multi-variant support, sample allocation, winner evaluation, and holdout dispatch.

-- 1. Extend whatsapp_campaigns table with A/B testing columns
ALTER TABLE whatsapp_campaigns
    ADD COLUMN IF NOT EXISTS is_ab_test BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS ab_test_percentage INT DEFAULT 20,
    ADD COLUMN IF NOT EXISTS ab_winner_metric VARCHAR(30) DEFAULT 'CLICK_RATE',
    ADD COLUMN IF NOT EXISTS ab_evaluation_hours INT DEFAULT 4,
    ADD COLUMN IF NOT EXISTS ab_evaluation_ends_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS winning_variant_id UUID,
    ADD COLUMN IF NOT EXISTS ab_status VARCHAR(30) DEFAULT 'DISABLED';

-- 2. Create whatsapp_campaign_variants table
CREATE TABLE IF NOT EXISTS whatsapp_campaign_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    campaign_id UUID NOT NULL REFERENCES whatsapp_campaigns(id) ON DELETE CASCADE,
    variant_label VARCHAR(10) NOT NULL, -- 'A', 'B', 'C', 'D'
    template_name VARCHAR(255) NOT NULL,
    template_snapshot_id UUID REFERENCES whatsapp_template_snapshots(id) ON DELETE SET NULL,
    variable_mapping_json TEXT,
    sample_percentage INT DEFAULT 50, -- Percentage of test sample allocated to this variant
    sample_count INT DEFAULT 0,
    sent_count INT DEFAULT 0,
    delivered_count INT DEFAULT 0,
    read_count INT DEFAULT 0,
    click_count INT DEFAULT 0,
    is_winner BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

-- 3. Extend whatsapp_campaign_recipients table with variant attribution and holdout flag
ALTER TABLE whatsapp_campaign_recipients
    ADD COLUMN IF NOT EXISTS variant_id UUID REFERENCES whatsapp_campaign_variants(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS is_holdout BOOLEAN DEFAULT FALSE;

-- 4. High-performance indexes
CREATE INDEX IF NOT EXISTS idx_wa_variants_camp ON whatsapp_campaign_variants(campaign_id);
CREATE INDEX IF NOT EXISTS idx_wa_variants_tenant ON whatsapp_campaign_variants(tenant_id);
CREATE INDEX IF NOT EXISTS idx_wa_recp_variant ON whatsapp_campaign_recipients(variant_id);
CREATE INDEX IF NOT EXISTS idx_wa_recp_camp_holdout ON whatsapp_campaign_recipients(campaign_id, is_holdout);
