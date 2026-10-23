-- =====================================================================
-- Migration V11052: Purge Legacy Plans (PRO, MIN) and Enforce Canonical Plans
-- =====================================================================

-- 1. Migrate existing tenants with legacy plan types to canonical plans (PRO -> GROWTH)
UPDATE tenants 
SET plan_type = 'GROWTH' 
WHERE plan_type IN ('PRO', 'MIN');

-- 2. Migrate tenant_subscriptions referencing legacy plan_id to GROWTH
UPDATE tenant_subscriptions 
SET plan_id = 'GROWTH' 
WHERE plan_id IN ('PRO', 'MIN');

-- 3. Delete legacy plans from subscription_plans table
DELETE FROM subscription_plans 
WHERE id IN ('PRO', 'MIN');

-- 4. Recreate check constraint on tenants.plan_type strictly for canonical 5 tiers
ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_plan_type_check;

ALTER TABLE tenants ADD CONSTRAINT tenants_plan_type_check 
CHECK (plan_type::text = ANY (ARRAY[
    'FREE'::character varying, 
    'STARTER'::character varying, 
    'GROWTH'::character varying, 
    'SCALE'::character varying, 
    'ENTERPRISE'::character varying
]::text[]));
