CREATE TABLE commerce_products (
    id UUID PRIMARY KEY,
    catalog_id UUID NOT NULL REFERENCES commerce_catalogs(id) ON DELETE CASCADE,
    product_retailer_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    image_url VARCHAR(1024),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    meta_product_id VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_commerce_products_catalog_retailer UNIQUE (catalog_id, product_retailer_id)
);

CREATE INDEX idx_commerce_products_catalog_id ON commerce_products(catalog_id);
