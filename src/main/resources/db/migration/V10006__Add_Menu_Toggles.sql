-- V10006: Add menu toggles for Trust, Offer, and SOS
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS show_trust_button BOOLEAN DEFAULT TRUE;
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS show_offer_button BOOLEAN DEFAULT TRUE;
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS show_sos_button BOOLEAN DEFAULT TRUE;
