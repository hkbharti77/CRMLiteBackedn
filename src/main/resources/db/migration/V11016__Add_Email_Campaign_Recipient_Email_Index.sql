-- V11016: Index email column on email_campaign_recipient for fast inbound reply attribution
CREATE INDEX IF NOT EXISTS idx_email_recipient_lower_email_created 
ON email_campaign_recipient (LOWER(email), created_at DESC);
