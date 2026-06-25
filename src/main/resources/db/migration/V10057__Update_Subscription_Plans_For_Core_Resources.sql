-- Rename limits in subscription_plans
ALTER TABLE subscription_plans RENAME COLUMN lead_limit TO primary_resource_limit;
ALTER TABLE subscription_plans RENAME COLUMN booking_limit TO secondary_resource_limit;

-- Add primary_resource to tenants with a default
ALTER TABLE tenants ADD COLUMN primary_resource VARCHAR(50) DEFAULT 'LEAD' NOT NULL;
