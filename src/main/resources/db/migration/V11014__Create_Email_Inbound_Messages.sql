-- V11013: Add Inbound Email Reply Tracking, Tokens, and Inbound Messages Table

-- 1. Add reply_token column (nullable initially for safe backfill)
ALTER TABLE email_campaign_recipient ADD COLUMN IF NOT EXISTS reply_token VARCHAR(64);

-- 2. Backfill existing rows with 64-char random tokens (compatible with standard PostgreSQL)
UPDATE email_campaign_recipient 
SET reply_token = replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '') 
WHERE reply_token IS NULL OR reply_token = '';

-- 3. Enforce NOT NULL on reply_token
ALTER TABLE email_campaign_recipient ALTER COLUMN reply_token SET NOT NULL;

-- 4. Unique constraint on reply_token
CREATE UNIQUE INDEX IF NOT EXISTS idx_email_recipient_reply_token ON email_campaign_recipient (reply_token);

-- 5. Add outbound last_message_id and reply engagement columns
ALTER TABLE email_campaign_recipient ADD COLUMN IF NOT EXISTS last_message_id VARCHAR(255);
ALTER TABLE email_campaign_recipient ADD COLUMN IF NOT EXISTS replied_at TIMESTAMPTZ;
ALTER TABLE email_campaign_recipient ADD COLUMN IF NOT EXISTS reply_count INT NOT NULL DEFAULT 0;

-- 6. Index on last_message_id for In-Reply-To header matching
CREATE INDEX IF NOT EXISTS idx_email_recipient_last_msg_id ON email_campaign_recipient (last_message_id);

-- 7. Create email_inbound_messages table
CREATE TABLE IF NOT EXISTS email_inbound_messages (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    campaign_id UUID,
    recipient_id UUID,
    campaign_recipient_id UUID REFERENCES email_campaign_recipient(id) ON DELETE SET NULL,
    reply_token VARCHAR(64),
    provider VARCHAR(50) NOT NULL,
    provider_message_id VARCHAR(255) NOT NULL,
    message_id VARCHAR(255),
    in_reply_to VARCHAR(255),
    references_header TEXT,
    from_email VARCHAR(255) NOT NULL,
    to_email VARCHAR(255) NOT NULL,
    subject TEXT,
    text_body TEXT,
    html_body TEXT,
    reply_snippet VARCHAR(500),
    attribution_status VARCHAR(20) NOT NULL DEFAULT 'ATTRIBUTED',
    received_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_inbound_provider_msg UNIQUE(provider, provider_message_id)
);

-- 8. Performance Indexes
CREATE INDEX IF NOT EXISTS idx_inbound_tenant_campaign ON email_inbound_messages (tenant_id, campaign_id);
CREATE INDEX IF NOT EXISTS idx_inbound_recipient_received ON email_inbound_messages (campaign_recipient_id, received_at DESC);
CREATE INDEX IF NOT EXISTS idx_inbound_message_id ON email_inbound_messages (message_id);
CREATE INDEX IF NOT EXISTS idx_inbound_in_reply_to ON email_inbound_messages (in_reply_to);
