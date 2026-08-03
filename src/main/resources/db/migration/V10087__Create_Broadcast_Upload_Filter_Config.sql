-- Stores per-tenant column filter configuration for CSV/Excel broadcast uploads
-- Admin defines which uploaded columns are available as audience filters
CREATE TABLE IF NOT EXISTS broadcast_upload_filter_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    filter_columns_json TEXT NOT NULL DEFAULT '[]',
    filter_rules_json TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_broadcast_filter_config_tenant UNIQUE (tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_broadcast_filter_config_tenant ON broadcast_upload_filter_configs(tenant_id);
