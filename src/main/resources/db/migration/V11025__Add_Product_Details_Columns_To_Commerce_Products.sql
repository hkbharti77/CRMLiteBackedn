-- Migration: Add sale_price, category, availability, product_condition, and url to commerce_products
ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS sale_price NUMERIC(12, 2);
ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS category VARCHAR(255);
ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS availability VARCHAR(50);
ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS product_condition VARCHAR(50);
ALTER TABLE commerce_products ADD COLUMN IF NOT EXISTS url VARCHAR(1024);
