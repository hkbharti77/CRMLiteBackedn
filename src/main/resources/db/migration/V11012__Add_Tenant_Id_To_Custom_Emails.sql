-- V11012: Add tenant_id to custom_emails table for multi-tenant isolation
ALTER TABLE custom_emails 
ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_custom_emails_tenant ON custom_emails (tenant_id);
