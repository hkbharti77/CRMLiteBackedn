-- Flyway Migration V11011: Create Voice Assistant Configs & Add Intent Fields to Tenant Flow Configs

CREATE TABLE IF NOT EXISTS voice_assistant_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    assistant_name VARCHAR(100) NOT NULL DEFAULT 'Assistant',
    greeting_text TEXT NOT NULL DEFAULT 'Hello! How can I help you today?',
    persona_prompt TEXT NOT NULL DEFAULT 'You are a helpful, professional AI voice assistant.',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by UUID REFERENCES app_users(id)
);

ALTER TABLE tenant_flow_configs 
    ADD COLUMN IF NOT EXISTS intent_description TEXT,
    ADD COLUMN IF NOT EXISTS trigger_examples JSONB DEFAULT '[]'::jsonb;

-- Seed default voice_assistant_configs for all existing tenants
INSERT INTO voice_assistant_configs (tenant_id, assistant_name, greeting_text, persona_prompt, enabled, version, created_at, updated_at)
SELECT id, 'Assistant', 'Hello! How can I help you today?', 'You are a helpful, professional AI voice assistant.', TRUE, 0, NOW(), NOW()
FROM tenants
ON CONFLICT (tenant_id) DO NOTHING;

-- Seed default intent_description and trigger_examples for existing tenant_flow_configs
UPDATE tenant_flow_configs
SET intent_description = CASE 
    WHEN flow_type = 'LEAD_CAPTURE' THEN 'Creates a new lead for a prospective customer. Use when the caller wants to enquire, leave details, or request a callback.'
    WHEN flow_type = 'APPOINTMENT' THEN 'Books an appointment for the caller at a specific date and time.'
    WHEN flow_type = 'BOOKING' THEN 'Creates a booking for an event, class, or service for the caller.'
    WHEN flow_type = 'SUPPORT' THEN 'Submits a support ticket or records an issue raised by the caller.'
    ELSE intent_description
END
WHERE intent_description IS NULL OR intent_description = '';

UPDATE tenant_flow_configs
SET trigger_examples = CASE 
    WHEN flow_type = 'LEAD_CAPTURE' THEN '["I want to inquire about pricing", "Can someone call me back?", "I would like more information"]'::jsonb
    WHEN flow_type = 'APPOINTMENT' THEN '["I want to book an appointment", "Can I schedule a consultation?", "I need to see a specialist"]'::jsonb
    WHEN flow_type = 'BOOKING' THEN '["I want to reserve a slot", "Book a ticket for me", "Reserve a class session"]'::jsonb
    WHEN flow_type = 'SUPPORT' THEN '["I have an issue with my order", "I need help with my account", "File a complaint"]'::jsonb
    ELSE trigger_examples
END
WHERE trigger_examples IS NULL OR trigger_examples = '[]'::jsonb;
