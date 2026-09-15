-- V10091__Add_Email_Scheduling_And_Snapshots.sql

-- 1. Modify custom_emails
ALTER TABLE custom_emails
ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS started_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS total_recipients INT DEFAULT 0,
ADD COLUMN IF NOT EXISTS processed_recipients INT DEFAULT 0,
ADD COLUMN IF NOT EXISTS sent_count INT DEFAULT 0,
ADD COLUMN IF NOT EXISTS failed_count INT DEFAULT 0,
ADD COLUMN IF NOT EXISTS snapshot_id UUID,
ADD COLUMN IF NOT EXISTS version INT DEFAULT 1;

-- Expand EmailStatus implicitly by changing app layer, assuming it's stored as VARCHAR

-- 2. Create email_campaign_snapshots
CREATE TABLE IF NOT EXISTS email_campaign_snapshots (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    subject VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    cta_label VARCHAR(255),
    cta_url TEXT,
    sender_name VARCHAR(255),
    sender_email VARCHAR(255),
    reply_to VARCHAR(255),
    audience_type VARCHAR(50),
    audience_filter_json TEXT,
    template_variables_json TEXT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_email_snapshots_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_email_snapshots_campaign FOREIGN KEY (campaign_id) REFERENCES custom_emails(id)
);

CREATE INDEX IF NOT EXISTS idx_email_snapshots_tenant ON email_campaign_snapshots(tenant_id);
CREATE INDEX IF NOT EXISTS idx_email_snapshots_campaign ON email_campaign_snapshots(campaign_id);

-- 3. Create email_campaign_audit_logs
CREATE TABLE IF NOT EXISTS email_campaign_audit_logs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    actor_user_id UUID,
    action VARCHAR(50) NOT NULL,
    details_json TEXT,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_email_audit_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_email_audit_campaign FOREIGN KEY (campaign_id) REFERENCES custom_emails(id),
    CONSTRAINT fk_email_audit_actor FOREIGN KEY (actor_user_id) REFERENCES app_users(id)
);

CREATE INDEX IF NOT EXISTS idx_email_audit_tenant ON email_campaign_audit_logs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_email_audit_campaign ON email_campaign_audit_logs(campaign_id);
