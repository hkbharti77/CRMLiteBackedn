-- V10088: Add WhatsApp Campaign Limit to Subscription Plans

-- 1. Add has_whatsapp_campaign and whatsapp_campaign_limit columns
ALTER TABLE subscription_plans
ADD COLUMN IF NOT EXISTS has_whatsapp_campaign BOOLEAN DEFAULT FALSE NOT NULL,
ADD COLUMN IF NOT EXISTS whatsapp_campaign_limit INT DEFAULT 0 NOT NULL;

-- 2. Update default limits for existing plans
UPDATE subscription_plans 
SET has_whatsapp_campaign = FALSE, whatsapp_campaign_limit = 0 
WHERE id = 'FREE';

UPDATE subscription_plans 
SET has_whatsapp_campaign = TRUE, whatsapp_campaign_limit = 2500 
WHERE id IN ('MIN', 'STARTER');

UPDATE subscription_plans 
SET has_whatsapp_campaign = TRUE, whatsapp_campaign_limit = 25000 
WHERE id = 'PRO';

UPDATE subscription_plans 
SET has_whatsapp_campaign = TRUE, whatsapp_campaign_limit = 1000000 
WHERE id = 'ENTERPRISE';
