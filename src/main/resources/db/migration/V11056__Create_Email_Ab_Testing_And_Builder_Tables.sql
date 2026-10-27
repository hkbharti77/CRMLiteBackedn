-- ============================================================================
-- Migration V11056: Create Email Campaign A/B Testing & Visual Builder Tables
-- ============================================================================

-- 1. Extend custom_emails with A/B split testing & visual builder state
ALTER TABLE custom_emails ADD COLUMN IF NOT EXISTS is_ab_test BOOLEAN DEFAULT FALSE;
ALTER TABLE custom_emails ADD COLUMN IF NOT EXISTS ab_test_percentage INT DEFAULT 20;
ALTER TABLE custom_emails ADD COLUMN IF NOT EXISTS ab_winner_metric VARCHAR(30) DEFAULT 'OPEN_RATE';
ALTER TABLE custom_emails ADD COLUMN IF NOT EXISTS ab_evaluation_hours INT DEFAULT 6;
ALTER TABLE custom_emails ADD COLUMN IF NOT EXISTS ab_evaluation_ends_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE custom_emails ADD COLUMN IF NOT EXISTS winning_variant_id UUID;
ALTER TABLE custom_emails ADD COLUMN IF NOT EXISTS ab_status VARCHAR(20) DEFAULT NULL;
ALTER TABLE custom_emails ADD COLUMN IF NOT EXISTS builder_json TEXT;

-- 2. Extend email_templates to persist modular drag-and-drop builder state
ALTER TABLE email_templates ADD COLUMN IF NOT EXISTS builder_json TEXT;

-- 3. Create email_campaign_variants table
CREATE TABLE IF NOT EXISTS email_campaign_variants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    campaign_id UUID NOT NULL REFERENCES custom_emails(id) ON DELETE CASCADE,
    variant_code VARCHAR(5) NOT NULL, -- 'A', 'B'
    name VARCHAR(255) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    builder_json TEXT,
    sample_count INT DEFAULT 0,
    sent_count INT DEFAULT 0,
    delivered_count INT DEFAULT 0,
    opened_count INT DEFAULT 0,
    clicked_count INT DEFAULT 0,
    is_winner BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

-- 4. Extend email_campaign_recipient with variant reference and holdout flag
ALTER TABLE email_campaign_recipient ADD COLUMN IF NOT EXISTS variant_id UUID REFERENCES email_campaign_variants(id) ON DELETE SET NULL;
ALTER TABLE email_campaign_recipient ADD COLUMN IF NOT EXISTS is_holdout BOOLEAN DEFAULT FALSE;

-- 5. Performance and querying indexes
CREATE INDEX IF NOT EXISTS idx_email_variants_camp ON email_campaign_variants(campaign_id);
CREATE INDEX IF NOT EXISTS idx_email_recipients_variant ON email_campaign_recipient(variant_id);
CREATE INDEX IF NOT EXISTS idx_email_recipients_holdout ON email_campaign_recipient(campaign_id, is_holdout, delivery_status);
CREATE INDEX IF NOT EXISTS idx_custom_emails_ab_status ON custom_emails(ab_status) WHERE is_ab_test = TRUE;
