-- Flyway migration: V11002__Index_Campaign_Recipients_Count.sql
-- Add composite index on campaign_id and status to optimize countByCampaignAndStatusIn queries (<5ms)
CREATE INDEX IF NOT EXISTS idx_campaign_recipients_campaign_status 
ON whatsapp_campaign_recipients (campaign_id, status);
