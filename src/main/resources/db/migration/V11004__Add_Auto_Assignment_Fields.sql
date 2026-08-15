-- Add Tenant configurations
ALTER TABLE tenants
ADD COLUMN IF NOT EXISTS auto_assignment_delay_minutes INTEGER DEFAULT 5,
ADD COLUMN IF NOT EXISTS default_daily_lead_limit INTEGER DEFAULT 10;

-- Add Agent configuration
ALTER TABLE app_users
ADD COLUMN IF NOT EXISTS daily_lead_limit INTEGER;

-- Add Lead assignment fields
ALTER TABLE leads
ADD COLUMN IF NOT EXISTS assigned_agent_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
ADD COLUMN IF NOT EXISTS pool_entry_time TIMESTAMP,
ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS assignment_source VARCHAR(50),
ADD COLUMN IF NOT EXISTS assignment_status VARCHAR(50) DEFAULT 'UNASSIGNED';

-- Create Lead Assignments table
CREATE TABLE IF NOT EXISTS lead_assignments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    lead_id UUID NOT NULL REFERENCES leads(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    assigned_at TIMESTAMP NOT NULL
);

-- Indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_lead_assignments_agent_date ON lead_assignments(agent_id, assigned_at);
CREATE INDEX IF NOT EXISTS idx_lead_assignment ON leads(tenant_id, assigned_agent_id, assignment_status, pool_entry_time);
