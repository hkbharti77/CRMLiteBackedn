-- Fix support_form_configs table schema to match the Java model

-- Add missing columns
ALTER TABLE support_form_configs 
ADD COLUMN IF NOT EXISTS phone_required BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS category_required BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS rate_limit_enabled BOOLEAN DEFAULT TRUE,
ADD COLUMN IF NOT EXISTS duplicate_detection_enabled BOOLEAN DEFAULT TRUE,
ADD COLUMN IF NOT EXISTS auto_assign_agent_id UUID REFERENCES app_users(id),
ADD COLUMN IF NOT EXISTS default_priority VARCHAR(20) DEFAULT 'MEDIUM',
ADD COLUMN IF NOT EXISTS primary_color VARCHAR(7) DEFAULT '#667eea',
ADD COLUMN IF NOT EXISTS logo_url TEXT;

-- Migrate data from old columns to new columns
UPDATE support_form_configs 
SET phone_required = require_phone,
    category_required = COALESCE(require_phone, FALSE)
WHERE phone_required IS NULL;

-- Drop old columns that are no longer needed
ALTER TABLE support_form_configs 
DROP COLUMN IF EXISTS collect_phone,
DROP COLUMN IF EXISTS require_phone,
DROP COLUMN IF EXISTS custom_fields;

-- Add constraints for the new columns
ALTER TABLE support_form_configs 
ALTER COLUMN phone_required SET NOT NULL,
ALTER COLUMN category_required SET NOT NULL,
ALTER COLUMN rate_limit_enabled SET NOT NULL,
ALTER COLUMN duplicate_detection_enabled SET NOT NULL;

-- Update existing records to have proper default values
UPDATE support_form_configs 
SET 
    phone_required = COALESCE(phone_required, FALSE),
    category_required = COALESCE(category_required, FALSE),
    rate_limit_enabled = COALESCE(rate_limit_enabled, TRUE),
    duplicate_detection_enabled = COALESCE(duplicate_detection_enabled, TRUE),
    default_priority = COALESCE(default_priority, 'MEDIUM'),
    primary_color = COALESCE(primary_color, '#667eea')
WHERE phone_required IS NULL 
   OR category_required IS NULL 
   OR rate_limit_enabled IS NULL 
   OR duplicate_detection_enabled IS NULL;

-- Create index for auto_assign_agent_id
CREATE INDEX IF NOT EXISTS idx_support_form_configs_auto_assign_agent_id 
ON support_form_configs(auto_assign_agent_id);