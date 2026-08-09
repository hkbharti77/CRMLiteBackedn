-- V10089: Add Priority and Queuing Fields to WhatsApp Campaigns and Recipients

-- 1. Add priority, priority_rank, and priority_locked to whatsapp_campaigns
ALTER TABLE whatsapp_campaigns
ADD COLUMN IF NOT EXISTS priority VARCHAR(20) DEFAULT 'LOW' NOT NULL,
ADD COLUMN IF NOT EXISTS priority_rank INT DEFAULT 1 NOT NULL,
ADD COLUMN IF NOT EXISTS priority_locked BOOLEAN DEFAULT FALSE NOT NULL;

-- 2. Add available_at, attempt_count, next_attempt_at, and idempotency_key to whatsapp_campaign_recipients
ALTER TABLE whatsapp_campaign_recipients
ADD COLUMN IF NOT EXISTS available_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
ADD COLUMN IF NOT EXISTS attempt_count INT DEFAULT 0 NOT NULL,
ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);

-- 3. Create high-performance compound indexes for priority scheduling queries
CREATE INDEX IF NOT EXISTS idx_campaign_dispatch ON whatsapp_campaigns (status, priority_rank DESC, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_recipient_queue ON whatsapp_campaign_recipients (campaign_id, status, available_at ASC);
