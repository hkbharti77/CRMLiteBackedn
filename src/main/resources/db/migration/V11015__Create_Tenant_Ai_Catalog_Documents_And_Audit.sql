-- V11015: Create Tenant AI Catalogs, AI Action Logs, and configuration fields

CREATE TABLE IF NOT EXISTS tenant_ai_catalogs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    ai_trigger_instruction TEXT NOT NULL,
    media_type VARCHAR(30) NOT NULL,            -- 'DOCUMENT', 'IMAGE'
    mime_type VARCHAR(100) NOT NULL,            -- 'application/pdf', 'image/png', etc.
    file_name VARCHAR(255) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    cloudinary_public_id TEXT NOT NULL,
    cloudinary_resource_type VARCHAR(50) NOT NULL,
    cloudinary_asset_id TEXT,
    cloudinary_url TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE', -- 'ACTIVE', 'DISABLED', 'PROCESSING', 'DELETING', 'DELETED', 'FAILED'
    created_by UUID,
    updated_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tenant_ai_catalogs_tenant ON tenant_ai_catalogs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_tenant_ai_catalogs_status ON tenant_ai_catalogs(tenant_id, status);

CREATE TABLE IF NOT EXISTS ai_action_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    conversation_id UUID,
    contact_id UUID,
    message_id VARCHAR(255),
    action_type VARCHAR(50) NOT NULL,           -- 'SEND_CATALOG', 'CLARIFY', 'NONE'
    catalog_id UUID REFERENCES tenant_ai_catalogs(id) ON DELETE SET NULL,
    model VARCHAR(100),
    decision_source VARCHAR(50) NOT NULL,       -- 'NATIVE_TOOL', 'STRUCTURED_FALLBACK', 'TEST_SIMULATION'
    raw_action TEXT,
    reason TEXT,
    caption TEXT,
    validation_stage VARCHAR(50),               -- 'PASSED', 'TENANT_MISMATCH', 'INACTIVE', 'COOLDOWN', 'IDEMPOTENCY_DUPLICATE', 'NOT_ELIGIBLE'
    idempotency_key VARCHAR(255),
    provider_message_id VARCHAR(255),
    delivery_status VARCHAR(50) DEFAULT 'PENDING', -- 'PENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED'
    validated BOOLEAN NOT NULL,
    executed BOOLEAN NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_action_logs_tenant ON ai_action_logs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_ai_action_logs_contact ON ai_action_logs(contact_id);
CREATE INDEX IF NOT EXISTS idx_ai_action_logs_idempotency ON ai_action_logs(idempotency_key);
CREATE INDEX IF NOT EXISTS idx_ai_action_logs_provider_msg ON ai_action_logs(provider_message_id);
CREATE INDEX IF NOT EXISTS idx_ai_action_logs_created_at ON ai_action_logs(created_at);

-- Add AI catalog settings to whatsapp_configs
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS enable_ai_catalogs BOOLEAN DEFAULT FALSE;
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS catalog_send_cooldown_seconds INT DEFAULT 300;
ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS catalog_relevance_threshold NUMERIC(3,2) DEFAULT 0.65;
