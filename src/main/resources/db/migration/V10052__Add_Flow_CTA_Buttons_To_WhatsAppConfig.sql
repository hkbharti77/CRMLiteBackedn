ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS flow_cancel_menu_json JSONB;
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS flow_completion_menu_json JSONB;
