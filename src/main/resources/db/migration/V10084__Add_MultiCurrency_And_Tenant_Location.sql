-- V10084: Add Multi-Currency Support (INR / USD) & Tenant Region/Timezone Data

-- 1. Add Dual Currency Pricing to subscription_plans
ALTER TABLE subscription_plans 
ADD COLUMN IF NOT EXISTS price_monthly_inr NUMERIC(10,2) DEFAULT 0,
ADD COLUMN IF NOT EXISTS price_yearly_inr NUMERIC(10,2) DEFAULT 0,
ADD COLUMN IF NOT EXISTS price_monthly_usd NUMERIC(10,2) DEFAULT 0,
ADD COLUMN IF NOT EXISTS price_yearly_usd NUMERIC(10,2) DEFAULT 0;

-- Backfill initial dual currency prices for existing plans
UPDATE subscription_plans SET 
  price_monthly_inr = 0,
  price_yearly_inr = 0,
  price_monthly_usd = 0,
  price_yearly_usd = 0
WHERE id = 'FREE';

UPDATE subscription_plans SET 
  price_monthly_inr = 1499.00,
  price_yearly_inr = 14390.00,
  price_monthly_usd = 19.99,
  price_yearly_usd = 189.90
WHERE id IN ('MIN', 'STARTER');

UPDATE subscription_plans SET 
  price_monthly_inr = 2499.00,
  price_yearly_inr = 23990.00,
  price_monthly_usd = 29.99,
  price_yearly_usd = 287.90
WHERE id = 'PRO';

UPDATE subscription_plans SET 
  price_monthly_inr = 6499.00,
  price_yearly_inr = 62390.00,
  price_monthly_usd = 79.99,
  price_yearly_usd = 767.90
WHERE id = 'ENTERPRISE';

-- 2. Add Region, Currency & Timezone to tenants table
ALTER TABLE tenants
ADD COLUMN IF NOT EXISTS country VARCHAR(10) DEFAULT 'IN',
ADD COLUMN IF NOT EXISTS currency VARCHAR(10) DEFAULT 'INR',
ADD COLUMN IF NOT EXISTS timezone VARCHAR(50) DEFAULT 'Asia/Kolkata';
