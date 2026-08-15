ALTER TABLE whatsapp_campaign_recipients ALTER COLUMN contact_id DROP NOT NULL;

-- Drop the existing unique constraint on (campaign_id, contact_id) because multiple recipients without a contact could violate it depending on DB behavior, and we want to prevent duplicate phone numbers anyway.
ALTER TABLE whatsapp_campaign_recipients DROP CONSTRAINT IF EXISTS uk_camp_contact;

-- Add a new unique constraint on (campaign_id, phone_number) to enforce no duplicate deliveries per campaign.
ALTER TABLE whatsapp_campaign_recipients ADD CONSTRAINT uk_camp_phone UNIQUE (campaign_id, phone_number);
