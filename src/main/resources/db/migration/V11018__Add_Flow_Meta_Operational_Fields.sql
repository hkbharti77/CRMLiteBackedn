-- Add meta operational fields to WhatsAppFlow
ALTER TABLE whatsapp_flows 
ADD COLUMN active_revision_id UUID REFERENCES flow_revisions(id) ON DELETE SET NULL,
ADD COLUMN active_meta_flow_id VARCHAR(255);

-- Add meta operational fields to FlowRevision
ALTER TABLE flow_revisions 
ADD COLUMN meta_flow_id VARCHAR(255),
ADD COLUMN meta_name VARCHAR(255),
ADD COLUMN is_deprecated BOOLEAN DEFAULT FALSE;

-- Add flow_token to FlowSubmission
ALTER TABLE flow_submissions 
ADD COLUMN flow_token VARCHAR(255);

-- Create FlowSendSession table for nfm_reply correlation
CREATE TABLE flow_send_sessions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    flow_token VARCHAR(255) NOT NULL,
    flow_id UUID REFERENCES whatsapp_flows(id) ON DELETE SET NULL,
    revision_id UUID REFERENCES flow_revisions(id) ON DELETE SET NULL,
    meta_flow_id VARCHAR(255),
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    contact_id UUID REFERENCES contacts(id) ON DELETE SET NULL,
    CONSTRAINT uk_flow_send_sessions_token UNIQUE (tenant_id, flow_token)
);

CREATE INDEX idx_flow_send_sessions_token ON flow_send_sessions(tenant_id, flow_token);
CREATE INDEX idx_flow_send_sessions_expires ON flow_send_sessions(expires_at);
