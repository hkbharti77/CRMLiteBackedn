-- V10042: Add app_secret field to whatsapp_configs for webhook signature verification
-- This allows each tenant to have their own Meta App Secret for multi-tenant support

ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS app_secret VARCHAR(255);

-- Add comment for documentation
COMMENT ON COLUMN whatsapp_configs.app_secret IS 'Meta App Secret for webhook signature verification';