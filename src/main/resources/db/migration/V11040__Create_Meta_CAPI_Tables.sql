ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS dataset_id VARCHAR(255);

CREATE TABLE IF NOT EXISTS whatsapp_attributions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    conversation_id UUID,
    waba_id VARCHAR(255),
    phone_number_id VARCHAR(255),
    ctwa_clid VARCHAR(255),
    source_type VARCHAR(255),
    source_id VARCHAR(255),
    captured_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_wa_attr_tenant_conv ON whatsapp_attributions (tenant_id, conversation_id);
CREATE INDEX IF NOT EXISTS idx_wa_attr_tenant_phone ON whatsapp_attributions (tenant_id, phone_number_id);

CREATE TABLE IF NOT EXISTS meta_conversion_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    lead_id UUID,
    event_id VARCHAR(255) NOT NULL,
    event_name VARCHAR(255) NOT NULL,
    source_type VARCHAR(255),
    waba_id VARCHAR(255),
    dataset_id VARCHAR(255),
    ctwa_clid VARCHAR(255),
    payload_version VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    last_attempt_at TIMESTAMP,
    meta_trace_id VARCHAR(255),
    last_http_status INT,
    last_error_code VARCHAR(255),
    last_error_message TEXT,
    locked_at TIMESTAMP,
    locked_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    sent_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_meta_conv_tenant_status_next ON meta_conversion_events (tenant_id, status, next_attempt_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_meta_conv_tenant_event_id ON meta_conversion_events (tenant_id, event_id);
