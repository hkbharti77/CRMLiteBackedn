-- V11051: Canonical Subscription Plans, WhatsApp Coexistence / Legacy Billing Modes, and AI Credit Ledger

-- 1. Update check constraint on tenants.plan_type to support canonical plans
ALTER TABLE tenants DROP CONSTRAINT IF EXISTS tenants_plan_type_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_plan_type_check 
    CHECK (((plan_type)::text = ANY (ARRAY[
        ('FREE'::character varying)::text, 
        ('STARTER'::character varying)::text, 
        ('GROWTH'::character varying)::text, 
        ('SCALE'::character varying)::text, 
        ('PRO'::character varying)::text, 
        ('ENTERPRISE'::character varying)::text,
        ('MIN'::character varying)::text
    ])));

-- 2. Add AI credit balance to tenants table
ALTER TABLE tenants
ADD COLUMN IF NOT EXISTS ai_credit_balance BIGINT NOT NULL DEFAULT 500;

-- 3. Add AI and Voice feature flags & included credits to subscription_plans
ALTER TABLE subscription_plans
ADD COLUMN IF NOT EXISTS included_ai_credits INTEGER NOT NULL DEFAULT 0,
ADD COLUMN IF NOT EXISTS has_voice_bot BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS has_calling_agent BOOLEAN NOT NULL DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS has_whatsapp_coexistence BOOLEAN NOT NULL DEFAULT TRUE;

-- 4. Add integration mode and reply metering columns to whatsapp_configs
ALTER TABLE whatsapp_configs
ADD COLUMN IF NOT EXISTS integration_mode VARCHAR(30) NOT NULL DEFAULT 'COEXISTENCE',
ADD COLUMN IF NOT EXISTS monthly_free_replies_limit INTEGER NOT NULL DEFAULT 1000,
ADD COLUMN IF NOT EXISTS monthly_replies_used INTEGER NOT NULL DEFAULT 0,
ADD COLUMN IF NOT EXISTS service_reply_rate_inr NUMERIC(8,4) NOT NULL DEFAULT 0.1150,
ADD COLUMN IF NOT EXISTS last_reply_counter_reset_at TIMESTAMPTZ DEFAULT NOW();

-- 5. Create AI Credit Ledger Table
CREATE TABLE IF NOT EXISTS tenant_credit_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    category VARCHAR(50) NOT NULL, -- 'INCLUDED_PLAN', 'TOP_UP_PACK', 'USAGE_DEDUCTION', 'REFUND_ADJUSTMENT'
    description VARCHAR(255) NOT NULL,
    reference_id VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tenant_credit_tx_tenant_id ON tenant_credit_transactions(tenant_id, created_at DESC);

-- 6. Create Credit Top-Up Products Table
CREATE TABLE IF NOT EXISTS credit_top_up_products (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    credits INTEGER NOT NULL,
    price_inr NUMERIC(10,2) NOT NULL,
    price_usd NUMERIC(10,2) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 7. Seed Canonical AI Credit Top-Up Products
INSERT INTO credit_top_up_products (id, name, credits, price_inr, price_usd, is_active)
VALUES
    ('TOPUP_MINI', 'Mini Credit Pack', 1000, 199.00, 2.49, TRUE),
    ('TOPUP_STANDARD', 'Standard Credit Pack', 5000, 899.00, 10.99, TRUE),
    ('TOPUP_BUSINESS', 'Business Credit Pack', 15000, 2499.00, 29.99, TRUE)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    credits = EXCLUDED.credits,
    price_inr = EXCLUDED.price_inr,
    price_usd = EXCLUDED.price_usd,
    is_active = EXCLUDED.is_active;

-- 8. Seed / Update Canonical Plans
-- Free Plan
INSERT INTO subscription_plans (
    id, name, price_monthly, price_yearly, price_monthly_inr, price_yearly_inr, price_monthly_usd, price_yearly_usd,
    employee_limit, primary_resource_limit, secondary_resource_limit, ticket_limit, email_limit,
    has_whatsapp, has_whatsapp_campaign, whatsapp_campaign_limit, has_custom_widget, has_rag_llm, is_contact_us,
    included_ai_credits, has_voice_bot, has_calling_agent, has_whatsapp_coexistence
) VALUES (
    'FREE', 'Free Plan', 0.00, 0.00, 0.00, 0.00, 0.00, 0.00,
    1, 100, 15, 10, 500,
    FALSE, FALSE, 0, FALSE, FALSE, FALSE,
    500, FALSE, FALSE, FALSE
) ON CONFLICT (id) DO UPDATE SET
    name = 'Free Plan',
    included_ai_credits = 500;

-- Starter Plan (Target Public: ₹1,999/mo, $24.99/mo)
INSERT INTO subscription_plans (
    id, name, price_monthly, price_yearly, price_monthly_inr, price_yearly_inr, price_monthly_usd, price_yearly_usd,
    employee_limit, primary_resource_limit, secondary_resource_limit, ticket_limit, email_limit,
    has_whatsapp, has_whatsapp_campaign, whatsapp_campaign_limit, has_custom_widget, has_rag_llm, is_contact_us,
    included_ai_credits, has_voice_bot, has_calling_agent, has_whatsapp_coexistence
) VALUES (
    'STARTER', 'Starter Plan', 1999.00, 19190.00, 1999.00, 19190.00, 24.99, 239.90,
    3, 5000, 1000, 1000, 5000,
    TRUE, TRUE, 2500, FALSE, FALSE, FALSE,
    2500, FALSE, FALSE, TRUE
) ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    price_monthly = EXCLUDED.price_monthly,
    price_yearly = EXCLUDED.price_yearly,
    price_monthly_inr = EXCLUDED.price_monthly_inr,
    price_yearly_inr = EXCLUDED.price_yearly_inr,
    price_monthly_usd = EXCLUDED.price_monthly_usd,
    price_yearly_usd = EXCLUDED.price_yearly_usd,
    employee_limit = EXCLUDED.employee_limit,
    primary_resource_limit = EXCLUDED.primary_resource_limit,
    secondary_resource_limit = EXCLUDED.secondary_resource_limit,
    ticket_limit = EXCLUDED.ticket_limit,
    email_limit = EXCLUDED.email_limit,
    has_whatsapp = EXCLUDED.has_whatsapp,
    has_whatsapp_campaign = EXCLUDED.has_whatsapp_campaign,
    whatsapp_campaign_limit = EXCLUDED.whatsapp_campaign_limit,
    included_ai_credits = EXCLUDED.included_ai_credits,
    has_whatsapp_coexistence = EXCLUDED.has_whatsapp_coexistence;

-- Growth Plan (Target Public: ₹4,999/mo, $59.99/mo)
INSERT INTO subscription_plans (
    id, name, price_monthly, price_yearly, price_monthly_inr, price_yearly_inr, price_monthly_usd, price_yearly_usd,
    employee_limit, primary_resource_limit, secondary_resource_limit, ticket_limit, email_limit,
    has_whatsapp, has_whatsapp_campaign, whatsapp_campaign_limit, has_custom_widget, has_rag_llm, is_contact_us,
    included_ai_credits, has_voice_bot, has_calling_agent, has_whatsapp_coexistence
) VALUES (
    'GROWTH', 'Growth Plan', 4999.00, 47990.00, 4999.00, 47990.00, 59.99, 575.90,
    8, 20000, 10000, 10000, 25000,
    TRUE, TRUE, 25000, TRUE, TRUE, FALSE,
    10000, TRUE, FALSE, TRUE
) ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    price_monthly = EXCLUDED.price_monthly,
    price_yearly = EXCLUDED.price_yearly,
    price_monthly_inr = EXCLUDED.price_monthly_inr,
    price_yearly_inr = EXCLUDED.price_yearly_inr,
    price_monthly_usd = EXCLUDED.price_monthly_usd,
    price_yearly_usd = EXCLUDED.price_yearly_usd,
    employee_limit = EXCLUDED.employee_limit,
    primary_resource_limit = EXCLUDED.primary_resource_limit,
    secondary_resource_limit = EXCLUDED.secondary_resource_limit,
    ticket_limit = EXCLUDED.ticket_limit,
    email_limit = EXCLUDED.email_limit,
    has_whatsapp = EXCLUDED.has_whatsapp,
    has_whatsapp_campaign = EXCLUDED.has_whatsapp_campaign,
    whatsapp_campaign_limit = EXCLUDED.whatsapp_campaign_limit,
    has_custom_widget = EXCLUDED.has_custom_widget,
    has_rag_llm = EXCLUDED.has_rag_llm,
    included_ai_credits = EXCLUDED.included_ai_credits,
    has_voice_bot = EXCLUDED.has_voice_bot,
    has_calling_agent = EXCLUDED.has_calling_agent,
    has_whatsapp_coexistence = EXCLUDED.has_whatsapp_coexistence;

-- Scale Plan (Target Public: ₹9,999/mo, $119.99/mo)
INSERT INTO subscription_plans (
    id, name, price_monthly, price_yearly, price_monthly_inr, price_yearly_inr, price_monthly_usd, price_yearly_usd,
    employee_limit, primary_resource_limit, secondary_resource_limit, ticket_limit, email_limit,
    has_whatsapp, has_whatsapp_campaign, whatsapp_campaign_limit, has_custom_widget, has_rag_llm, is_contact_us,
    included_ai_credits, has_voice_bot, has_calling_agent, has_whatsapp_coexistence
) VALUES (
    'SCALE', 'Scale Plan', 9999.00, 95990.00, 9999.00, 95990.00, 119.99, 1151.90,
    20, 100000, 50000, 50000, 100000,
    TRUE, TRUE, 100000, TRUE, TRUE, FALSE,
    25000, TRUE, TRUE, TRUE
) ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    price_monthly = EXCLUDED.price_monthly,
    price_yearly = EXCLUDED.price_yearly,
    price_monthly_inr = EXCLUDED.price_monthly_inr,
    price_yearly_inr = EXCLUDED.price_yearly_inr,
    price_monthly_usd = EXCLUDED.price_monthly_usd,
    price_yearly_usd = EXCLUDED.price_yearly_usd,
    employee_limit = EXCLUDED.employee_limit,
    primary_resource_limit = EXCLUDED.primary_resource_limit,
    secondary_resource_limit = EXCLUDED.secondary_resource_limit,
    ticket_limit = EXCLUDED.ticket_limit,
    email_limit = EXCLUDED.email_limit,
    has_whatsapp = EXCLUDED.has_whatsapp,
    has_whatsapp_campaign = EXCLUDED.has_whatsapp_campaign,
    whatsapp_campaign_limit = EXCLUDED.whatsapp_campaign_limit,
    has_custom_widget = EXCLUDED.has_custom_widget,
    has_rag_llm = EXCLUDED.has_rag_llm,
    included_ai_credits = EXCLUDED.included_ai_credits,
    has_voice_bot = EXCLUDED.has_voice_bot,
    has_calling_agent = EXCLUDED.has_calling_agent,
    has_whatsapp_coexistence = EXCLUDED.has_whatsapp_coexistence;

-- Update Enterprise Plan
UPDATE subscription_plans
SET 
    name = 'Enterprise Custom',
    is_contact_us = TRUE,
    included_ai_credits = 100000,
    has_voice_bot = TRUE,
    has_calling_agent = TRUE,
    has_whatsapp_coexistence = TRUE
WHERE id = 'ENTERPRISE';

-- Preserve existing PRO plan for active grandfathered contracts
UPDATE subscription_plans
SET 
    name = 'Scale Professional (Legacy)',
    included_ai_credits = 10000,
    has_voice_bot = TRUE,
    has_calling_agent = FALSE,
    has_whatsapp_coexistence = TRUE
WHERE id = 'PRO';
