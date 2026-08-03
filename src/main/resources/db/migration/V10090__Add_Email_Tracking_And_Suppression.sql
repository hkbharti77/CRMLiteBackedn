CREATE TABLE IF NOT EXISTS email_campaign_recipient (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    tracking_token VARCHAR(255) UNIQUE NOT NULL,
    delivery_status VARCHAR(50) NOT NULL, -- PENDING, SENT, DELIVERED, BOUNCED, FAILED
    failed_at TIMESTAMP,
    failure_code VARCHAR(255),
    failure_message TEXT,
    sent_at TIMESTAMP,
    delivered_at TIMESTAMP,
    first_opened_at TIMESTAMP,
    first_clicked_at TIMESTAMP,
    unsubscribed_at TIMESTAMP,
    bounce_type VARCHAR(50), -- HARD, SOFT
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_tenant_campaign_email UNIQUE (tenant_id, campaign_id, email)
);

CREATE INDEX idx_email_campaign_recipient_campaign_id_status ON email_campaign_recipient (campaign_id, delivery_status);

CREATE TABLE IF NOT EXISTS email_recipient_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    recipient_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL, -- SENT, DELIVERED, OPENED, CLICKED, BOUNCED, COMPLAINT, UNSUBSCRIBED
    link_url TEXT,
    occurred_at TIMESTAMP NOT NULL,
    metadata JSONB
);

CREATE INDEX idx_email_recipient_event_recipient_event ON email_recipient_event (recipient_id, event_type);
CREATE INDEX idx_email_recipient_event_campaign_event ON email_recipient_event (campaign_id, event_type);

CREATE TABLE IF NOT EXISTS email_tracked_link (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    link_token VARCHAR(255) UNIQUE NOT NULL,
    destination_url TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_email_tracked_link_tenant_campaign ON email_tracked_link (tenant_id, campaign_id);

CREATE TABLE IF NOT EXISTS email_suppression_list (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    reason VARCHAR(50) NOT NULL, -- UNSUBSCRIBED, HARD_BOUNCE, SOFT_BOUNCE, COMPLAINT, MANUAL, INVALID
    source_campaign_id UUID,
    created_at TIMESTAMP NOT NULL,
    created_by UUID,
    CONSTRAINT uq_tenant_email UNIQUE (tenant_id, email)
);

CREATE TABLE IF NOT EXISTS email_provider_event (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    provider VARCHAR(100) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB,
    received_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_provider_event_id UNIQUE (provider, provider_event_id)
);
