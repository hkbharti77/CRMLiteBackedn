-- V10045: Add performance index on whatsapp_configs.verify_token
-- This prevents full table scans during frequent webhook verification requests from Meta (WhatsApp).

CREATE INDEX IF NOT EXISTS idx_whatsapp_configs_verify_token ON whatsapp_configs (verify_token);
