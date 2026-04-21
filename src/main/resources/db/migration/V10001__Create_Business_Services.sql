-- Create Business Services table for PostgreSQL
CREATE TABLE IF NOT EXISTS business_services (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    image_data BYTEA,
    image_content_type VARCHAR(255),
    image_url VARCHAR(255),
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_business_services_user FOREIGN KEY (user_id) REFERENCES app_users(id) ON DELETE CASCADE
);

-- Add index on user_id for faster lookups
CREATE INDEX IF NOT EXISTS idx_business_services_user_id ON business_services(user_id);
