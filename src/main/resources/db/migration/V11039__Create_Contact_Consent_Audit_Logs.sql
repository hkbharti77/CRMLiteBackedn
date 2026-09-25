-- Flyway Migration: V11039__Create_Contact_Consent_Audit_Logs.sql
-- Creates contact_consent_audit_logs table for tracking omnichannel consent status transitions and audits

CREATE TABLE IF NOT EXISTS contact_consent_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    contact_id UUID NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    channel VARCHAR(20) NOT NULL,
    previous_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    source VARCHAR(50) NOT NULL,
    reason VARCHAR(255),
    ip_address VARCHAR(50),
    user_agent VARCHAR(255),
    performed_by VARCHAR(100),
    timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_consent_audit_contact ON contact_consent_audit_logs(contact_id);
CREATE INDEX IF NOT EXISTS idx_consent_audit_tenant ON contact_consent_audit_logs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_consent_audit_channel ON contact_consent_audit_logs(channel);
CREATE INDEX IF NOT EXISTS idx_consent_audit_tenant_contact_time ON contact_consent_audit_logs(tenant_id, contact_id, timestamp DESC);
