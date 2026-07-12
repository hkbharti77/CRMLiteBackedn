-- V10069: Create custom_menu_cards table for tenant-defined widget menu builder
-- Tenants can override the niche-default cards with their own custom buttons.

CREATE TABLE IF NOT EXISTS custom_menu_cards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    section         VARCHAR(50)  NOT NULL DEFAULT 'SERVICES',
    title           VARCHAR(80)  NOT NULL,
    subtitle        VARCHAR(120),
    icon            VARCHAR(40)  NOT NULL DEFAULT 'briefcase',
    action_type     VARCHAR(20)  NOT NULL DEFAULT 'CATALOG',
    action_payload  VARCHAR(500) NOT NULL DEFAULT '',
    display_order   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_custom_menu_cards_tenant
    ON custom_menu_cards (tenant_id);
