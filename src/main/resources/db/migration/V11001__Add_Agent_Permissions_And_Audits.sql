-- Flyway migration: V11001__Add_Agent_Permissions_And_Audits.sql
-- Add permissions JSONB and permission_version columns to app_users table
ALTER TABLE app_users 
ADD COLUMN IF NOT EXISTS permissions JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE app_users 
ADD COLUMN IF NOT EXISTS permission_version INT NOT NULL DEFAULT 1;

-- Initialize default permissions for existing AGENT role users
UPDATE app_users 
SET permissions = '["MODULE_INBOX", "MODULE_LEADS", "MODULE_SETTINGS", "SETTINGS_PROFILE"]'::jsonb
WHERE role = 'AGENT' AND (permissions IS NULL OR permissions = '[]'::jsonb);

-- Create append-only user permission audit log table
CREATE TABLE IF NOT EXISTS user_permission_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    changed_by_id UUID NOT NULL,
    action VARCHAR(50) NOT NULL DEFAULT 'UPDATE_PERMISSIONS',
    old_permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    new_permissions JSONB NOT NULL DEFAULT '[]'::jsonb,
    reason VARCHAR(500),
    request_id VARCHAR(100),
    ip_address VARCHAR(100),
    user_agent VARCHAR(500),
    permission_version INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_perm_audit_tenant_agent ON user_permission_audit_logs(tenant_id, agent_id);
CREATE INDEX IF NOT EXISTS idx_perm_audit_created_at ON user_permission_audit_logs(created_at DESC);
