CREATE TABLE IF NOT EXISTS email_providers (
    id VARCHAR(50) PRIMARY KEY,
    business_id VARCHAR(50) NOT NULL,
    provider_type VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    from_email VARCHAR(255) NOT NULL,
    credentials_payload TEXT NOT NULL,
    is_default BOOLEAN DEFAULT FALSE,
    status VARCHAR(50) DEFAULT 'UNVERIFIED',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_email_providers_business ON email_providers(business_id);

-- Table custom_email_campaigns doesn't exist, skipping ALTER TABLE
