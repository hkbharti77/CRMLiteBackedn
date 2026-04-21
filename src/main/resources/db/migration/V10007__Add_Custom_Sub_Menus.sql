-- Add custom sub-menus field to whatsapp_configs
ALTER TABLE whatsapp_configs ADD COLUMN custom_sub_menus_json TEXT;
