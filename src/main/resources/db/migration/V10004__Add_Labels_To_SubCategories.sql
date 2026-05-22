-- Add dynamic button label columns to business sub categories
ALTER TABLE business_sub_categories ADD COLUMN IF NOT EXISTS trigger_label VARCHAR(50);
ALTER TABLE business_sub_categories ADD COLUMN IF NOT EXISTS services_label VARCHAR(50);
