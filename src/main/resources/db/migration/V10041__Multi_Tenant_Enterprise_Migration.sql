-- V10041: Multi-Tenant Enterprise Decoupling and RBAC Migration

-- 1. Create Tenants Table
CREATE TABLE IF NOT EXISTS tenants (
    id UUID PRIMARY KEY,
    business_name VARCHAR(255) NOT NULL,
    business_type VARCHAR(100),
    business_sub_type VARCHAR(100),
    address VARCHAR(500),
    about_us TEXT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    logo_url VARCHAR(500),
    plan_type VARCHAR(50) DEFAULT 'FREE' NOT NULL,
    onboarding_completed BOOLEAN DEFAULT FALSE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 2. PL/pgSQL block to handle decoupling conditionally and safely
DO $$
BEGIN
    -- Populate Tenants from App Users (Only if app_users has business_name column, meaning legacy schema is active)
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name='app_users' AND column_name='business_name'
    ) THEN
        INSERT INTO tenants (id, business_name, business_type, business_sub_type, address, about_us, latitude, longitude, logo_url, plan_type, onboarding_completed, created_at)
        SELECT id, business_name, business_type, business_sub_type, address, about_us, latitude, longitude, logo_url, COALESCE(plan_type, 'FREE'), COALESCE(onboarding_completed, FALSE), COALESCE(created_at, CURRENT_TIMESTAMP)
        FROM app_users
        ON CONFLICT (id) DO NOTHING;
    END IF;

    -- Add tenant_id to app_users if missing
    IF NOT EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name='app_users' AND column_name='tenant_id'
    ) THEN
        ALTER TABLE app_users ADD COLUMN tenant_id UUID;
        UPDATE app_users SET tenant_id = id WHERE tenant_id IS NULL;
        ALTER TABLE app_users ALTER COLUMN tenant_id SET NOT NULL;
    END IF;

    -- Add fk_users_tenant if missing
    IF NOT EXISTS (
        SELECT 1 
        FROM information_schema.table_constraints 
        WHERE table_name='app_users' AND constraint_name='fk_users_tenant'
    ) THEN
        ALTER TABLE app_users ADD CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
    END IF;

    -- Drop legacy columns from app_users
    ALTER TABLE app_users DROP COLUMN IF EXISTS business_name;
    ALTER TABLE app_users DROP COLUMN IF EXISTS business_type;
    ALTER TABLE app_users DROP COLUMN IF EXISTS business_sub_type;
    ALTER TABLE app_users DROP COLUMN IF EXISTS address;
    ALTER TABLE app_users DROP COLUMN IF EXISTS about_us;
    ALTER TABLE app_users DROP COLUMN IF EXISTS latitude;
    ALTER TABLE app_users DROP COLUMN IF EXISTS longitude;
    ALTER TABLE app_users DROP COLUMN IF EXISTS logo_url;
    ALTER TABLE app_users DROP COLUMN IF EXISTS onboarding_completed;
    ALTER TABLE app_users DROP COLUMN IF EXISTS plan_type;

    -- 5. Add tenant_id to Business Entities conditionally
    
    -- LEADS
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='leads' AND column_name='tenant_id') THEN
        ALTER TABLE leads ADD COLUMN tenant_id UUID;
        UPDATE leads SET tenant_id = owner_id WHERE tenant_id IS NULL;
        ALTER TABLE leads ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_name='leads' AND constraint_name='fk_leads_tenant') THEN
        ALTER TABLE leads ADD CONSTRAINT fk_leads_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
    END IF;

    -- CONTACTS
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='contacts' AND column_name='tenant_id') THEN
        ALTER TABLE contacts ADD COLUMN tenant_id UUID;
        UPDATE contacts SET tenant_id = owner_id WHERE tenant_id IS NULL;
        ALTER TABLE contacts ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_name='contacts' AND constraint_name='fk_contacts_tenant') THEN
        ALTER TABLE contacts ADD CONSTRAINT fk_contacts_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
    END IF;

    -- APPOINTMENTS
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='appointments' AND column_name='tenant_id') THEN
        ALTER TABLE appointments ADD COLUMN tenant_id UUID;
        UPDATE appointments SET tenant_id = owner_id WHERE tenant_id IS NULL;
        ALTER TABLE appointments ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_name='appointments' AND constraint_name='fk_appointments_tenant') THEN
        ALTER TABLE appointments ADD CONSTRAINT fk_appointments_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
    END IF;

    -- CHAT_MESSAGES (renamed messages -> chat_messages)
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='chat_messages' AND column_name='tenant_id') THEN
        ALTER TABLE chat_messages ADD COLUMN tenant_id UUID;
        UPDATE chat_messages SET tenant_id = owner_id WHERE tenant_id IS NULL;
        ALTER TABLE chat_messages ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_name='chat_messages' AND constraint_name='fk_messages_tenant') THEN
        ALTER TABLE chat_messages ADD CONSTRAINT fk_messages_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
    END IF;

    -- TICKETS
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='tickets' AND column_name='tenant_id') THEN
        ALTER TABLE tickets ADD COLUMN tenant_id UUID;
        UPDATE tickets SET tenant_id = owner_id WHERE tenant_id IS NULL;
        ALTER TABLE tickets ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_name='tickets' AND constraint_name='fk_tickets_tenant') THEN
        ALTER TABLE tickets ADD CONSTRAINT fk_tickets_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
    END IF;

    -- BUSINESS_SERVICES
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='business_services' AND column_name='tenant_id') THEN
        ALTER TABLE business_services ADD COLUMN tenant_id UUID;
        UPDATE business_services SET tenant_id = owner_id WHERE tenant_id IS NULL;
        ALTER TABLE business_services ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_name='business_services' AND constraint_name='fk_business_services_tenant') THEN
        ALTER TABLE business_services ADD CONSTRAINT fk_business_services_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
    END IF;

    -- 6. Decouple whatsapp_configs from users and link to tenants
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='whatsapp_configs' AND column_name='tenant_id') THEN
        ALTER TABLE whatsapp_configs ADD COLUMN tenant_id UUID;
        UPDATE whatsapp_configs SET tenant_id = user_id WHERE tenant_id IS NULL;
        ALTER TABLE whatsapp_configs ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_name='whatsapp_configs' AND constraint_name='fk_whatsapp_config_tenant') THEN
        ALTER TABLE whatsapp_configs ADD CONSTRAINT fk_whatsapp_config_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_name='whatsapp_configs' AND constraint_name='unique_tenant_whatsapp') THEN
        ALTER TABLE whatsapp_configs ADD CONSTRAINT unique_tenant_whatsapp UNIQUE (tenant_id);
    END IF;
    ALTER TABLE whatsapp_configs DROP COLUMN IF EXISTS user_id;

END $$;

-- 7. Create Tables for Staff Invitations, Profiles, Permissions, and Audits
CREATE TABLE IF NOT EXISTS staff_invites (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email VARCHAR(254) NOT NULL,
    role VARCHAR(20) NOT NULL,
    hashed_token VARCHAR(64) UNIQUE NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_profiles (
    id UUID PRIMARY KEY,
    user_id UUID UNIQUE NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    department VARCHAR(100),
    specialization VARCHAR(100),
    is_available BOOLEAN DEFAULT TRUE NOT NULL,
    max_concurrent_chats INTEGER DEFAULT 5 NOT NULL,
    last_active_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS role_permission_configs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    permissions JSONB NOT NULL,
    UNIQUE(tenant_id, role)
);

CREATE TABLE IF NOT EXISTS permission_audit_logs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    performed_by_id UUID NOT NULL REFERENCES app_users(id),
    target_user_id UUID NOT NULL REFERENCES app_users(id),
    action VARCHAR(100) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- 8. Apply Core Multi-Tenant Scaling Indexes (Enhancement #1)
CREATE INDEX IF NOT EXISTS idx_users_tenant_id ON app_users(tenant_id);
CREATE INDEX IF NOT EXISTS idx_agent_profiles_tenant_id ON agent_profiles(tenant_id);
CREATE INDEX IF NOT EXISTS idx_staff_invites_tenant_id ON staff_invites(tenant_id);
CREATE INDEX IF NOT EXISTS idx_leads_tenant_id ON leads(tenant_id);
CREATE INDEX IF NOT EXISTS idx_contacts_tenant_id ON contacts(tenant_id);
CREATE INDEX IF NOT EXISTS idx_appointments_tenant_id ON appointments(tenant_id);
CREATE INDEX IF NOT EXISTS idx_permission_audit_tenant_id ON permission_audit_logs(tenant_id);
