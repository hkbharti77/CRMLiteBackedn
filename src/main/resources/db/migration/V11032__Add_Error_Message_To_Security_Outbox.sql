-- V11032: Add error_message to Outbox tables

ALTER TABLE security_notification_outbox
ADD COLUMN IF NOT EXISTS error_message TEXT;

ALTER TABLE campaign_pause_outbox
ADD COLUMN IF NOT EXISTS error_message TEXT;
