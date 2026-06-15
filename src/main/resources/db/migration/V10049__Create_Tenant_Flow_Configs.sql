CREATE TABLE IF NOT EXISTS tenant_flow_configs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id UUID NOT NULL,
    flow_type VARCHAR(255) NOT NULL,
    configuration_json JSONB NOT NULL,
    template_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tenant_flow_configs_user FOREIGN KEY (tenant_id) REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT uk_tenant_flow_type UNIQUE (tenant_id, flow_type)
);

CREATE INDEX IF NOT EXISTS idx_tenant_flow_configs_tenant_id ON tenant_flow_configs(tenant_id);
