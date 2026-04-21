-- V10005: Add dynamic menu fields for Trust, Socials, Offer, and SOS
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS review_url VARCHAR(500);
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS portfolio_url VARCHAR(500);
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS offer_text TEXT;
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS sos_note VARCHAR(255);
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS third_button_type VARCHAR(50);
