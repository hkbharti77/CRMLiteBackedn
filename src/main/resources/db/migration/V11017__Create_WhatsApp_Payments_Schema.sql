-- Flyway Migration: Create WhatsApp Payments & Outbox Architecture Tables
-- V11017__Create_WhatsApp_Payments_Schema.sql

-- 1. Tenant Payment Configs
CREATE TABLE IF NOT EXISTS tenant_payment_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    integration_type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    webhook_key_hash VARCHAR(64) NOT NULL UNIQUE,
    whatsapp_account_id UUID REFERENCES whatsapp_configs(id) ON DELETE SET NULL,
    meta_payment_configuration_name VARCHAR(100),
    encrypted_key_id TEXT,
    encrypted_key_secret TEXT,
    encrypted_webhook_secret TEXT,
    currency VARCHAR(3) DEFAULT 'INR',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tenant_integration UNIQUE (tenant_id, integration_type),
    CONSTRAINT uk_tenant_config_composite UNIQUE (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_pay_config_tenant ON tenant_payment_configs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_pay_config_hash ON tenant_payment_configs(webhook_key_hash);

-- 2. WhatsApp Orders
CREATE TABLE IF NOT EXISTS whatsapp_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    reference_id VARCHAR(100) NOT NULL,
    external_reference_id VARCHAR(100),
    customer_id UUID,
    customer_wa_id VARCHAR(50) NOT NULL,
    customer_name VARCHAR(150),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    subtotal_minor BIGINT NOT NULL,
    discount_minor BIGINT NOT NULL DEFAULT 0,
    tax_minor BIGINT NOT NULL DEFAULT 0,
    shipping_minor BIGINT NOT NULL DEFAULT 0,
    total_minor BIGINT NOT NULL,
    order_status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    payment_status VARCHAR(50) NOT NULL DEFAULT 'CREATED',
    fulfillment_status VARCHAR(50) NOT NULL DEFAULT 'UNFULFILLED',
    preferred_payment_mode VARCHAR(50),
    preferred_payment_provider VARCHAR(50),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP WITH TIME ZONE,
    expired_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_tenant_order_ref UNIQUE (tenant_id, reference_id),
    CONSTRAINT uk_orders_composite UNIQUE (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_orders_tenant_ref ON whatsapp_orders(tenant_id, reference_id);
CREATE INDEX IF NOT EXISTS idx_orders_tenant_wa ON whatsapp_orders(tenant_id, customer_wa_id);
CREATE INDEX IF NOT EXISTS idx_orders_tenant_status ON whatsapp_orders(tenant_id, payment_status, order_status);

-- 3. WhatsApp Order Items
CREATE TABLE IF NOT EXISTS whatsapp_order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    order_id UUID NOT NULL REFERENCES whatsapp_orders(id) ON DELETE CASCADE,
    sku VARCHAR(100),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    quantity INTEGER NOT NULL,
    unit_price_minor BIGINT NOT NULL,
    tax_minor BIGINT NOT NULL DEFAULT 0,
    discount_minor BIGINT NOT NULL DEFAULT 0,
    line_total_minor BIGINT NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_order_items_order ON whatsapp_order_items(order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_tenant ON whatsapp_order_items(tenant_id);

-- 4. Payment Transactions
CREATE TABLE IF NOT EXISTS payment_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    order_id UUID NOT NULL REFERENCES whatsapp_orders(id) ON DELETE CASCADE,
    payment_integration_id UUID REFERENCES tenant_payment_configs(id) ON DELETE SET NULL,
    provider VARCHAR(50) NOT NULL,
    payment_mode VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    provider_order_id VARCHAR(100),
    provider_payment_id VARCHAR(100),
    provider_transaction_id VARCHAR(100),
    checkout_url TEXT,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    raw_reference TEXT,
    failure_code VARCHAR(100),
    failure_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_tx_composite UNIQUE (id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_tx_order_tenant ON payment_transactions(tenant_id, order_id);
CREATE INDEX IF NOT EXISTS idx_tx_provider_pay ON payment_transactions(tenant_id, provider_payment_id);
CREATE INDEX IF NOT EXISTS idx_tx_pending_recon ON payment_transactions(status, created_at);

-- 5. Payment Refunds
CREATE TABLE IF NOT EXISTS payment_refunds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    order_id UUID NOT NULL REFERENCES whatsapp_orders(id) ON DELETE CASCADE,
    transaction_id UUID NOT NULL REFERENCES payment_transactions(id) ON DELETE CASCADE,
    payment_integration_id UUID REFERENCES tenant_payment_configs(id) ON DELETE SET NULL,
    provider_refund_id VARCHAR(150),
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_refunds_tenant_tx ON payment_refunds(tenant_id, transaction_id);
CREATE INDEX IF NOT EXISTS idx_refunds_tenant_order ON payment_refunds(tenant_id, order_id);

-- 6. Payment Webhook Events
CREATE TABLE IF NOT EXISTS payment_webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    payment_integration_id UUID NOT NULL REFERENCES tenant_payment_configs(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,
    provider_event_key VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    signature_valid BOOLEAN NOT NULL DEFAULT FALSE,
    payload_hash VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL,
    processing_status VARCHAR(50) NOT NULL DEFAULT 'RECEIVED',
    processed_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_integration_event UNIQUE (payment_integration_id, provider_event_key)
);

CREATE INDEX IF NOT EXISTS idx_webhook_status ON payment_webhook_events(processing_status, provider);
CREATE INDEX IF NOT EXISTS idx_webhook_tenant ON payment_webhook_events(tenant_id);

-- 7. Payment Outbox Events
CREATE TABLE IF NOT EXISTS payment_outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    order_id UUID NOT NULL REFERENCES whatsapp_orders(id) ON DELETE CASCADE,
    transaction_id UUID REFERENCES payment_transactions(id) ON DELETE SET NULL,
    payment_integration_id UUID REFERENCES tenant_payment_configs(id) ON DELETE SET NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 5,
    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMP WITH TIME ZONE,
    locked_by VARCHAR(100),
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_outbox_poll ON payment_outbox_events(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_outbox_tenant ON payment_outbox_events(tenant_id);

-- 8. Payment Audit Logs
CREATE TABLE IF NOT EXISTS payment_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    order_id UUID,
    transaction_id UUID,
    refund_id UUID,
    actor_type VARCHAR(50) NOT NULL,
    actor_id VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    old_status VARCHAR(50),
    new_status VARCHAR(50),
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_order ON payment_audit_logs(tenant_id, order_id);
