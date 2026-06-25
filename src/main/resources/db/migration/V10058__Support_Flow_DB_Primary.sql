-- V10058: Support flow steps are now stored per-tenant in tenant_flow_configs.
-- This migration ensures the existing table supports the new DB-primary approach.
--
-- Changes:
--   1. The unique constraint on (tenant_id, flow_type) already prevents duplicates.
--      No schema changes are needed since each tenant gets their own row which is
--      auto-seeded on first access from support.json.
--   2. Add a partial index for fast lookup of SUPPORT configs specifically.

CREATE INDEX IF NOT EXISTS idx_tenant_flow_configs_support
    ON tenant_flow_configs (tenant_id)
    WHERE flow_type = 'SUPPORT';

-- Grant a comment for documentation
COMMENT ON TABLE tenant_flow_configs IS
    'Stores per-tenant WhatsApp flow step customizations. '
    'For SUPPORT flow type, this is the primary source of truth — '
    'auto-seeded from support.json on first tenant access.';
