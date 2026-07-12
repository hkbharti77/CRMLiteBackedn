-- V10074: Remove Trust and Offer buttons and their config columns
ALTER TABLE whatsapp_configs DROP COLUMN IF EXISTS show_trust_button;
ALTER TABLE whatsapp_configs DROP COLUMN IF EXISTS show_offer_button;
ALTER TABLE whatsapp_configs DROP COLUMN IF EXISTS review_url;
ALTER TABLE whatsapp_configs DROP COLUMN IF EXISTS offer_text;
