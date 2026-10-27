-- Migration V11057: Create Canned Responses Table
CREATE TABLE IF NOT EXISTS canned_responses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title VARCHAR(150) NOT NULL,
    shortcut VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50) DEFAULT 'GENERAL',
    is_shared BOOLEAN DEFAULT TRUE,
    usage_count BIGINT NOT NULL DEFAULT 0,
    created_by_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_tenant_shortcut UNIQUE(tenant_id, shortcut)
);

CREATE INDEX IF NOT EXISTS idx_canned_resp_tenant ON canned_responses(tenant_id);
CREATE INDEX IF NOT EXISTS idx_canned_resp_shortcut ON canned_responses(tenant_id, shortcut);
CREATE INDEX IF NOT EXISTS idx_canned_resp_category ON canned_responses(tenant_id, category);
