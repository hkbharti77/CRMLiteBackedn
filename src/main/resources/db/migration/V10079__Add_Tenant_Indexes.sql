-- Add missing tenant_id performance indexes

-- Index for fetching subscriptions by tenant
CREATE INDEX IF NOT EXISTS idx_tenant_subscriptions_tenant_id ON tenant_subscriptions(tenant_id);

-- Index for fetching users by tenant (e.g. findFirstUserIdByTenantId)
CREATE INDEX IF NOT EXISTS idx_app_users_tenant_id ON app_users(tenant_id);

-- Index for fetching whatsapp config by tenant
CREATE INDEX IF NOT EXISTS idx_whatsapp_configs_tenant_id ON whatsapp_configs(tenant_id);
