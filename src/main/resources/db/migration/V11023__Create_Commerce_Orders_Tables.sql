CREATE TABLE commerce_orders (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    waba_id VARCHAR(255) NOT NULL,
    phone_number_id VARCHAR(255) NOT NULL,
    catalog_id VARCHAR(255) NOT NULL,
    customer_id UUID,
    conversation_id UUID,
    whatsapp_message_id VARCHAR(255) NOT NULL,
    customer_wa_id VARCHAR(255) NOT NULL,
    customer_phone VARCHAR(255),
    customer_name VARCHAR(255),
    customer_note TEXT,
    currency VARCHAR(10) NOT NULL,
    subtotal DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    discount DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    tax DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    shipping DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    total DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    payment_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, whatsapp_message_id)
);

CREATE TABLE commerce_order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES commerce_orders(id) ON DELETE CASCADE,
    product_retailer_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    item_price DECIMAL(12, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL
);
