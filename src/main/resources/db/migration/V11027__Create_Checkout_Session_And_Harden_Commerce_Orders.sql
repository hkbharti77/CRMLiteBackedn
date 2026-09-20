-- 1. Catalog Payment Configuration
ALTER TABLE commerce_catalogs
  ADD COLUMN IF NOT EXISTS online_payment_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS cod_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Checkout Configuration Snapshot & Detailed Customer Fields on Orders
ALTER TABLE commerce_orders
  ADD COLUMN IF NOT EXISTS online_payment_enabled_at_order BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS cod_enabled_at_order BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS shipping_name VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS address_line1 VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS address_line2 VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS city VARCHAR(100) NULL,
  ADD COLUMN IF NOT EXISTS state VARCHAR(100) NULL,
  ADD COLUMN IF NOT EXISTS postal_code VARCHAR(20) NULL,
  ADD COLUMN IF NOT EXISTS country VARCHAR(10) DEFAULT 'IN',
  ADD COLUMN IF NOT EXISTS shipping_address TEXT NULL,
  ADD COLUMN IF NOT EXISTS customer_email VARCHAR(255) NULL,
  ADD COLUMN IF NOT EXISTS checkout_status VARCHAR(50) NOT NULL DEFAULT 'AWAITING_CUSTOMER_DETAILS',
  ADD COLUMN IF NOT EXISTS payment_status VARCHAR(40) NOT NULL DEFAULT 'UNPAID',
  ADD COLUMN IF NOT EXISTS payment_method VARCHAR(30) NULL,
  ADD COLUMN IF NOT EXISTS payment_provider VARCHAR(50) NULL,
  ADD COLUMN IF NOT EXISTS payment_link_id VARCHAR(100) NULL,
  ADD COLUMN IF NOT EXISTS payment_link_url TEXT NULL,
  ADD COLUMN IF NOT EXISTS payment_reference_id VARCHAR(100) NULL,
  ADD COLUMN IF NOT EXISTS payment_amount DECIMAL(12, 2) NULL,
  ADD COLUMN IF NOT EXISTS paid_at TIMESTAMP NULL;

-- 3. Dedicated Durable Checkout Session Table
CREATE TABLE IF NOT EXISTS commerce_checkout_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  customer_wa_id VARCHAR(50) NOT NULL,
  order_id UUID NOT NULL REFERENCES commerce_orders(id) ON DELETE CASCADE,
  checkout_step VARCHAR(50) NOT NULL, -- 'AWAITING_ADDRESS', 'AWAITING_PAYMENT_CHOICE', 'AWAITING_PAYMENT', 'COMPLETED', 'EXPIRED', 'SUPERSEDED'
  expires_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Partial index allows multiple historical completed/expired sessions per customer, but only ONE active session!
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_customer_active_checkout 
  ON commerce_checkout_sessions(tenant_id, customer_wa_id) 
  WHERE checkout_step IN ('AWAITING_ADDRESS', 'AWAITING_PAYMENT_CHOICE', 'AWAITING_PAYMENT');

CREATE INDEX IF NOT EXISTS idx_checkout_session_order ON commerce_checkout_sessions(order_id);
CREATE INDEX IF NOT EXISTS idx_commerce_orders_payment_link ON commerce_orders(payment_link_id);

-- 4. Event-Level Payment Webhook Idempotency Table
CREATE TABLE IF NOT EXISTS payment_webhook_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  event_id VARCHAR(150) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  provider VARCHAR(50) NOT NULL,
  payload JSONB NULL,
  processed_at TIMESTAMP NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_payment_webhook_event UNIQUE (tenant_id, event_id)
);
