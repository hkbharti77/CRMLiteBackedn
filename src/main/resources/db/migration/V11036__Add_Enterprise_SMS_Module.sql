-- Flyway Migration: V11036__Add_Enterprise_SMS_Module.sql
-- Description: Schema creation for multi-tenant Enterprise SMS Module (Providers, Templates, Campaigns, Recipients, Messages, Delivery Events, Suppressions)

-- 1. SMS Provider Configurations
CREATE TABLE IF NOT EXISTS sms_providers (
    id VARCHAR(50) PRIMARY KEY,
    business_id VARCHAR(50) NOT NULL,
    provider_type VARCHAR(50) NOT NULL, -- TWILIO, MSG91, FAST2SMS, AWS_SNS
    name VARCHAR(255) NOT NULL,
    sender_id VARCHAR(50) NOT NULL,
    credentials_payload TEXT NOT NULL, -- Encrypted JSON
    requests_per_second INT DEFAULT 10,
    is_default BOOLEAN DEFAULT FALSE,
    status VARCHAR(50) DEFAULT 'UNVERIFIED', -- CONNECTED, ERROR, UNVERIFIED
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sms_providers_business ON sms_providers(business_id);

-- 2. DLT & Compliance SMS Templates
CREATE TABLE IF NOT EXISTS sms_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50) DEFAULT 'TRANSACTIONAL', -- TRANSACTIONAL, PROMOTIONAL, OTP
    country_code VARCHAR(10) DEFAULT 'IN',
    sender_id VARCHAR(50),
    dlt_entity_id VARCHAR(100),   -- India DLT Principal Entity ID
    dlt_template_id VARCHAR(100), -- India DLT Registered Template ID
    dlt_header_id VARCHAR(100),   -- India DLT Header ID / Sender ID
    allowed_variables JSONB,      -- ["lead_name", "booking_time"]
    status VARCHAR(50) DEFAULT 'APPROVED',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sms_templates_business ON sms_templates(business_id);

-- 3. SMS Campaigns
CREATE TABLE IF NOT EXISTS sms_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    template_id UUID REFERENCES sms_templates(id) ON DELETE SET NULL,
    provider_id VARCHAR(50) REFERENCES sms_providers(id) ON DELETE SET NULL,
    status VARCHAR(50) DEFAULT 'DRAFT', -- DRAFT, SCHEDULED, PROCESSING, COMPLETED, FAILED
    total_recipients INT DEFAULT 0,
    delivered_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    scheduled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sms_campaigns_business ON sms_campaigns(business_id);

-- 4. SMS Campaign Recipients
CREATE TABLE IF NOT EXISTS sms_campaign_recipients (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES sms_campaigns(id) ON DELETE CASCADE,
    contact_id UUID,
    phone_number VARCHAR(30) NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING', -- PENDING, QUEUED, SENT, DELIVERED, FAILED, SUPPRESSED
    provider_message_id VARCHAR(255),
    error_code VARCHAR(50),
    error_message TEXT,
    segments INT DEFAULT 1,
    unit_cost NUMERIC(12,6) DEFAULT 0.000000,
    total_cost NUMERIC(12,6) DEFAULT 0.000000,
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT uq_sms_campaign_recipient UNIQUE (campaign_id, phone_number)
);

CREATE INDEX IF NOT EXISTS idx_sms_camp_recip_campaign ON sms_campaign_recipients(campaign_id);
CREATE INDEX IF NOT EXISTS idx_sms_camp_recip_status ON sms_campaign_recipients(campaign_id, status);
CREATE INDEX IF NOT EXISTS idx_sms_camp_recip_contact ON sms_campaign_recipients(contact_id);
CREATE INDEX IF NOT EXISTS idx_sms_camp_recip_provider_msg ON sms_campaign_recipients(provider_message_id);

-- 5. SMS Messages (Unified Chat Message Extension / SMS Ledger)
CREATE TABLE IF NOT EXISTS sms_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    contact_id UUID,
    message_id UUID REFERENCES chat_messages(id) ON DELETE SET NULL,
    campaign_id UUID REFERENCES sms_campaigns(id) ON DELETE SET NULL,
    campaign_recipient_id UUID REFERENCES sms_campaign_recipients(id) ON DELETE SET NULL,
    provider_id VARCHAR(50),
    provider_type VARCHAR(50) NOT NULL,
    provider_message_id VARCHAR(255), -- Nullable initially (QUEUED state)
    direction VARCHAR(20) NOT NULL,   -- INCOMING, OUTGOING
    phone_number VARCHAR(30) NOT NULL,
    content TEXT NOT NULL,
    encoding VARCHAR(20) DEFAULT 'GSM-7', -- GSM-7, UNICODE
    segments INT DEFAULT 1,
    delivery_status VARCHAR(50) DEFAULT 'QUEUED', -- QUEUED, SENT, DELIVERED, FAILED
    error_code VARCHAR(50),
    error_message TEXT,
    unit_cost NUMERIC(12,6) DEFAULT 0.000000,
    total_cost NUMERIC(12,6) DEFAULT 0.000000,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sms_messages_provider_msg 
ON sms_messages(provider_type, provider_message_id) 
WHERE provider_message_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sms_messages_business ON sms_messages(business_id);
CREATE INDEX IF NOT EXISTS idx_sms_messages_campaign ON sms_messages(campaign_id);
CREATE INDEX IF NOT EXISTS idx_sms_messages_contact ON sms_messages(contact_id);

-- 6. SMS Delivery Events (DLR Audit Trail)
CREATE TABLE IF NOT EXISTS sms_delivery_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_type VARCHAR(50) NOT NULL,
    provider_message_id VARCHAR(255) NOT NULL,
    provider_event_id VARCHAR(255),
    event_type VARCHAR(50) NOT NULL, -- QUEUED, SENT, DELIVERED, UNDELIVERED, FAILED
    raw_payload JSONB,
    occurred_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sms_deliv_evt_msg ON sms_delivery_events(provider_type, provider_message_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sms_delivery_event_id ON sms_delivery_events(provider_type, provider_event_id) WHERE provider_event_id IS NOT NULL;

-- 7. SMS Suppression & Opt-Out Registry
CREATE TABLE IF NOT EXISTS sms_suppressions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id VARCHAR(50) NOT NULL,
    contact_id UUID,
    phone_number VARCHAR(30) NOT NULL,
    reason VARCHAR(100) DEFAULT 'USER_OPT_OUT',
    source VARCHAR(50) DEFAULT 'INBOUND_STOP',
    suppressed_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_sms_suppression UNIQUE (business_id, phone_number)
);

CREATE INDEX IF NOT EXISTS idx_sms_suppress_biz_phone ON sms_suppressions(business_id, phone_number);
