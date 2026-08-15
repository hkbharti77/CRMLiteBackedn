-- V10085: Add is_contact_us column to subscription_plans table
ALTER TABLE subscription_plans 
ADD COLUMN IF NOT EXISTS is_contact_us BOOLEAN DEFAULT false;

-- Mark ENTERPRISE plan as contact us by default
UPDATE subscription_plans SET is_contact_us = true WHERE id = 'ENTERPRISE';

-- Cleanup any stale MAX test plan row
DELETE FROM subscription_plans WHERE id = 'MAX';
