-- V10056: Create Subscription And Billing Tables

-- 1. Create Subscription Plans Table
CREATE TABLE IF NOT EXISTS subscription_plans (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    price_monthly DECIMAL(10,2) NOT NULL,
    price_yearly DECIMAL(10,2) NOT NULL,
    employee_limit INT NOT NULL,
    lead_limit INT DEFAULT 0,
    booking_limit INT DEFAULT 0,
    ticket_limit INT NOT NULL,
    email_limit INT NOT NULL,
    has_whatsapp BOOLEAN DEFAULT FALSE NOT NULL,
    has_custom_widget BOOLEAN DEFAULT FALSE NOT NULL
);

-- Ensure all columns exist if table was created earlier without these columns
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS name VARCHAR(100);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS price_monthly DECIMAL(10,2);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS price_yearly DECIMAL(10,2);
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS employee_limit INT;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS lead_limit INT DEFAULT 0;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS booking_limit INT DEFAULT 0;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS primary_resource_limit INT DEFAULT 0;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS secondary_resource_limit INT DEFAULT 0;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS ticket_limit INT;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS email_limit INT;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS has_whatsapp BOOLEAN DEFAULT FALSE;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS has_custom_widget BOOLEAN DEFAULT FALSE;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS has_rag_llm BOOLEAN DEFAULT TRUE;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS is_contact_us BOOLEAN DEFAULT FALSE;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS has_whatsapp_campaign BOOLEAN DEFAULT FALSE;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS whatsapp_campaign_limit INT DEFAULT 0;

-- 2. Create Tenant Subscriptions Table
CREATE TABLE IF NOT EXISTS tenant_subscriptions (
    id UUID PRIMARY KEY,
    tenant_id UUID UNIQUE NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    plan_id VARCHAR(50) NOT NULL REFERENCES subscription_plans(id),
    status VARCHAR(50) NOT NULL, -- ACTIVE, PAST_DUE, CANCELLED, FREE_TRIAL
    billing_cycle VARCHAR(20) NOT NULL, -- MONTHLY, YEARLY
    current_period_start TIMESTAMP NOT NULL,
    current_period_end TIMESTAMP NOT NULL,
    stripe_subscription_id VARCHAR(100),
    razorpay_subscription_id VARCHAR(100)
);

-- 3. Create Billing Transactions Table
CREATE TABLE IF NOT EXISTS billing_transactions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    amount DECIMAL(10,2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(50) NOT NULL, -- SUCCESS, FAILED, PENDING
    payment_gateway VARCHAR(50) NOT NULL, -- STRIPE, RAZORPAY
    gateway_transaction_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 4. Seed Default Subscription Plans
DO $$
BEGIN
    -- Set defaults and drop NOT NULL for columns that might exist from out-of-order/future migrations
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='primary_resource_limit') THEN
        ALTER TABLE subscription_plans ALTER COLUMN primary_resource_limit DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN primary_resource_limit SET DEFAULT 0;
        UPDATE subscription_plans SET primary_resource_limit = 0 WHERE primary_resource_limit IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='secondary_resource_limit') THEN
        ALTER TABLE subscription_plans ALTER COLUMN secondary_resource_limit DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN secondary_resource_limit SET DEFAULT 0;
        UPDATE subscription_plans SET secondary_resource_limit = 0 WHERE secondary_resource_limit IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='has_rag_llm') THEN
        ALTER TABLE subscription_plans ALTER COLUMN has_rag_llm DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN has_rag_llm SET DEFAULT TRUE;
        UPDATE subscription_plans SET has_rag_llm = TRUE WHERE has_rag_llm IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='is_contact_us') THEN
        ALTER TABLE subscription_plans ALTER COLUMN is_contact_us DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN is_contact_us SET DEFAULT FALSE;
        UPDATE subscription_plans SET is_contact_us = FALSE WHERE is_contact_us IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='has_whatsapp_campaign') THEN
        ALTER TABLE subscription_plans ALTER COLUMN has_whatsapp_campaign DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN has_whatsapp_campaign SET DEFAULT FALSE;
        UPDATE subscription_plans SET has_whatsapp_campaign = FALSE WHERE has_whatsapp_campaign IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='whatsapp_campaign_limit') THEN
        ALTER TABLE subscription_plans ALTER COLUMN whatsapp_campaign_limit DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN whatsapp_campaign_limit SET DEFAULT 0;
        UPDATE subscription_plans SET whatsapp_campaign_limit = 0 WHERE whatsapp_campaign_limit IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='price_monthly_inr') THEN
        ALTER TABLE subscription_plans ALTER COLUMN price_monthly_inr DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN price_monthly_inr SET DEFAULT 0;
        UPDATE subscription_plans SET price_monthly_inr = 0 WHERE price_monthly_inr IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='price_yearly_inr') THEN
        ALTER TABLE subscription_plans ALTER COLUMN price_yearly_inr DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN price_yearly_inr SET DEFAULT 0;
        UPDATE subscription_plans SET price_yearly_inr = 0 WHERE price_yearly_inr IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='price_monthly_usd') THEN
        ALTER TABLE subscription_plans ALTER COLUMN price_monthly_usd DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN price_monthly_usd SET DEFAULT 0;
        UPDATE subscription_plans SET price_monthly_usd = 0 WHERE price_monthly_usd IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='price_yearly_usd') THEN
        ALTER TABLE subscription_plans ALTER COLUMN price_yearly_usd DROP NOT NULL;
        ALTER TABLE subscription_plans ALTER COLUMN price_yearly_usd SET DEFAULT 0;
        UPDATE subscription_plans SET price_yearly_usd = 0 WHERE price_yearly_usd IS NULL;
    END IF;

    INSERT INTO subscription_plans (id, name, price_monthly, price_yearly, employee_limit, lead_limit, booking_limit, ticket_limit, email_limit, has_whatsapp, has_custom_widget)
    VALUES 
    ('FREE', 'Free Starter Pack', 0.00, 0.00, 1, 100, 15, 10, 500, FALSE, FALSE),
    ('PRO', 'Scale Professional', 2999.00, 28790.00, 10, 1000000, 1000000, 1000000, 25000, TRUE, TRUE),
    ('ENTERPRISE', 'Enterprise Custom', 9999.00, 95990.00, 1000000, 1000000, 1000000, 1000000, 1000000, TRUE, TRUE)
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        price_monthly = EXCLUDED.price_monthly,
        price_yearly = EXCLUDED.price_yearly,
        employee_limit = EXCLUDED.employee_limit,
        lead_limit = EXCLUDED.lead_limit,
        booking_limit = EXCLUDED.booking_limit,
        ticket_limit = EXCLUDED.ticket_limit,
        email_limit = EXCLUDED.email_limit,
        has_whatsapp = EXCLUDED.has_whatsapp,
        has_custom_widget = EXCLUDED.has_custom_widget;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='primary_resource_limit') THEN
        UPDATE subscription_plans SET primary_resource_limit = COALESCE(lead_limit, 0) WHERE primary_resource_limit = 0 OR primary_resource_limit IS NULL;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='subscription_plans' AND column_name='secondary_resource_limit') THEN
        UPDATE subscription_plans SET secondary_resource_limit = COALESCE(booking_limit, 0) WHERE secondary_resource_limit = 0 OR secondary_resource_limit IS NULL;
    END IF;
END $$;

-- 5. Indexes for Query Performance
CREATE INDEX IF NOT EXISTS idx_tenant_subscriptions_tenant ON tenant_subscriptions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_billing_transactions_tenant ON billing_transactions(tenant_id);

