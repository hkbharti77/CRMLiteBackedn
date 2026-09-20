CREATE TABLE IF NOT EXISTS commerce_catalogs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    waba_id VARCHAR(255) NOT NULL,
    meta_catalog_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    is_visible BOOLEAN NOT NULL DEFAULT FALSE,
    cart_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_synced_at TIMESTAMP,
    last_verified_at TIMESTAMP,
    sync_status VARCHAR(30),
    sync_error TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uc_tenant_meta_catalog UNIQUE (tenant_id, meta_catalog_id)
);
