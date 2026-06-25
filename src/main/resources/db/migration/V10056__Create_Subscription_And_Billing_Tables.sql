-- V10056: Create Subscription And Billing Tables

-- 1. Create Subscription Plans Table
CREATE TABLE IF NOT EXISTS subscription_plans (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    price_monthly DECIMAL(10,2) NOT NULL,
    price_yearly DECIMAL(10,2) NOT NULL,
    employee_limit INT NOT NULL,
    lead_limit INT NOT NULL,
    booking_limit INT NOT NULL,
    ticket_limit INT NOT NULL,
    email_limit INT NOT NULL,
    has_whatsapp BOOLEAN DEFAULT FALSE NOT NULL,
    has_custom_widget BOOLEAN DEFAULT FALSE NOT NULL
);

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

-- 5. Indexes for Query Performance
CREATE INDEX IF NOT EXISTS idx_tenant_subscriptions_tenant ON tenant_subscriptions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_billing_transactions_tenant ON billing_transactions(tenant_id);
