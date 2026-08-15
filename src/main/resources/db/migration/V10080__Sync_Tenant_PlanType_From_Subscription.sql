-- V10080: Sync Tenant.plan_type from active TenantSubscription
-- Fixes: Custom Branding showing "Upgrade Required" for PRO/ENTERPRISE tenants
-- because the legacy plan_type column was never updated when subscriptions were activated.

UPDATE tenants t
SET plan_type = ts.plan_id
FROM tenant_subscriptions ts
WHERE ts.tenant_id = t.id
  AND ts.status = 'ACTIVE'
  AND ts.plan_id IN ('PRO', 'ENTERPRISE')
  AND t.plan_type = 'FREE';
