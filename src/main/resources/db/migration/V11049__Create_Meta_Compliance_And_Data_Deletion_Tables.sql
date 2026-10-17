-- V11049: Create Meta Compliance And Data Deletion Tables
-- Supports Meta WhatsApp Embedded Signup Deauthorization & GDPR Data Deletion Callbacks

-- 1. Add meta_user_id column and index to whatsapp_configs
ALTER TABLE whatsapp_configs
ADD COLUMN IF NOT EXISTS meta_user_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS idx_whatsapp_configs_meta_user_id ON whatsapp_configs(meta_user_id);

-- 2. Create meta_data_deletion_requests table
CREATE TABLE IF NOT EXISTS meta_data_deletion_requests (
    id UUID PRIMARY KEY,
    confirmation_code VARCHAR(64) NOT NULL UNIQUE,
    meta_user_id VARCHAR(100) NOT NULL,
    tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    data_summary TEXT,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_meta_deletion_code ON meta_data_deletion_requests(confirmation_code);
CREATE INDEX IF NOT EXISTS idx_meta_deletion_user ON meta_data_deletion_requests(meta_user_id);
CREATE INDEX IF NOT EXISTS idx_meta_deletion_status ON meta_data_deletion_requests(status);
