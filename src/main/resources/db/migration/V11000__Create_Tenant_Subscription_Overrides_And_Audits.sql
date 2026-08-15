-- V10090: Create Tenant Subscription Overrides and Audit History Tables

-- 1. Create tenant_subscription_overrides table
CREATE TABLE IF NOT EXISTS tenant_subscription_overrides (
    id UUID PRIMARY KEY,
    tenant_id UUID UNIQUE NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    feature_overrides JSONB,
    quota_overrides JSONB,
    priority_overrides JSONB,
    pricing_overrides JSONB,
    effective_from TIMESTAMP,
    effective_until TIMESTAMP,
    entity_version BIGINT DEFAULT 0 NOT NULL,
    version INT DEFAULT 1 NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 2. Create tenant_subscription_override_audits table
CREATE TABLE IF NOT EXISTS tenant_subscription_override_audits (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    action VARCHAR(50) NOT NULL,
    old_value_json JSONB,
    new_value_json JSONB,
    changed_by VARCHAR(100) NOT NULL,
    reason TEXT,
    request_id VARCHAR(100),
    ip_address VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 3. Indexes for fast lookup
CREATE INDEX IF NOT EXISTS idx_override_tenant ON tenant_subscription_overrides(tenant_id);
CREATE INDEX IF NOT EXISTS idx_override_audit_tenant ON tenant_subscription_override_audits(tenant_id);
