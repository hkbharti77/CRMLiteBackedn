-- Add show_about_contact column to whatsapp_configs table
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS show_about_contact BOOLEAN DEFAULT TRUE;
