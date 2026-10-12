-- Migration V11044: Harden SMS Multi-Tenant Schema and Migrate businessId to tenant_id UUID
-- Description: Adds tenant_id column with foreign key to tenants(id) across all SMS tables, backfills data, and adds indexes.

ALTER TABLE sms_campaigns ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE;
ALTER TABLE sms_templates ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE;
ALTER TABLE sms_messages ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE;
ALTER TABLE sms_suppressions ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE;
ALTER TABLE sms_providers ADD COLUMN IF NOT EXISTS tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE;

-- Backfill tenant_id from business_id where valid UUID format exists
UPDATE sms_campaigns 
SET tenant_id = business_id::uuid 
WHERE business_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' 
  AND tenant_id IS NULL;

UPDATE sms_templates 
SET tenant_id = business_id::uuid 
WHERE business_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' 
  AND tenant_id IS NULL;

UPDATE sms_messages 
SET tenant_id = business_id::uuid 
WHERE business_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' 
  AND tenant_id IS NULL;

UPDATE sms_suppressions 
SET tenant_id = business_id::uuid 
WHERE business_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' 
  AND tenant_id IS NULL;

UPDATE sms_providers 
SET tenant_id = business_id::uuid 
WHERE business_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' 
  AND tenant_id IS NULL;

-- Create performance indexes on tenant_id
CREATE INDEX IF NOT EXISTS idx_sms_campaigns_tenant ON sms_campaigns(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sms_templates_tenant ON sms_templates(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sms_messages_tenant ON sms_messages(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sms_suppressions_tenant ON sms_suppressions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_sms_providers_tenant ON sms_providers(tenant_id);
