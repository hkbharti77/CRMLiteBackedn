-- Migration V11048: Add scheduling metadata and recipient retries to SMS subsystem
ALTER TABLE sms_campaigns ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE sms_campaigns ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE sms_campaigns ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE sms_campaign_recipients ADD COLUMN IF NOT EXISTS retry_count INT DEFAULT 0;
ALTER TABLE sms_campaign_recipients ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP WITHOUT TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_sms_recipients_retry ON sms_campaign_recipients(campaign_id, status, next_attempt_at);
