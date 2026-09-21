-- V11033: Harden WhatsApp Configs and Campaigns indexes for high throughput and webhook verification
CREATE INDEX IF NOT EXISTS idx_whatsapp_configs_waba_id ON whatsapp_configs (waba_id);
CREATE INDEX IF NOT EXISTS idx_whatsapp_configs_phone_number_id ON whatsapp_configs (phone_number_id);
CREATE INDEX IF NOT EXISTS idx_whatsapp_campaigns_status ON whatsapp_campaigns (status);
