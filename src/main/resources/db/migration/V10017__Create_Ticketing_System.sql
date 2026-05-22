-- Create Ticketing System Tables
-- This migration creates the complete ticketing system including tickets, activities, and SLA configurations

-- Create tickets table
CREATE TABLE tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_number VARCHAR(50) UNIQUE NOT NULL,
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    contact_id UUID REFERENCES contacts(id) ON DELETE SET NULL,
    subject VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    submitter_name VARCHAR(255),
    submitter_email VARCHAR(255),
    submitter_phone VARCHAR(50),
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    priority VARCHAR(50) NOT NULL DEFAULT 'MEDIUM',
    source VARCHAR(50) NOT NULL DEFAULT 'MANUAL',
    category VARCHAR(100),
    assigned_to_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    first_response_due_at TIMESTAMP,
    resolution_due_at TIMESTAMP,
    first_responded_at TIMESTAMP,
    sla_breached BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    resolved_at TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP,
    deleted_by UUID REFERENCES app_users(id) ON DELETE SET NULL
);

-- Create ticket activities table for tracking all actions on tickets
CREATE TABLE ticket_activities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    activity_type VARCHAR(50) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    details TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create SLA configurations table
CREATE TABLE sla_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    priority VARCHAR(50) NOT NULL,
    first_response_hours INTEGER NOT NULL DEFAULT 24,
    resolution_hours INTEGER NOT NULL DEFAULT 72,
    business_hours_only BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE(owner_id, priority)
);

-- Create support form configurations table
CREATE TABLE support_form_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    form_title VARCHAR(255) NOT NULL DEFAULT 'Get Support',
    form_description TEXT,
    success_message TEXT,
    collect_phone BOOLEAN NOT NULL DEFAULT TRUE,
    require_phone BOOLEAN NOT NULL DEFAULT FALSE,
    categories TEXT, -- JSON array of category options
    custom_fields TEXT, -- JSON array of additional fields
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE(owner_id)
);

-- Create indexes for better performance
CREATE INDEX idx_tickets_owner_id ON tickets(owner_id);
CREATE INDEX idx_tickets_contact_id ON tickets(contact_id);
CREATE INDEX idx_tickets_assigned_to_id ON tickets(assigned_to_id);
CREATE INDEX idx_tickets_status ON tickets(status);
CREATE INDEX idx_tickets_priority ON tickets(priority);
CREATE INDEX idx_tickets_created_at ON tickets(created_at);
CREATE INDEX idx_tickets_ticket_number ON tickets(ticket_number);
CREATE INDEX idx_tickets_submitter_email ON tickets(submitter_email);
CREATE INDEX idx_tickets_deleted ON tickets(deleted);

CREATE INDEX idx_ticket_activities_ticket_id ON ticket_activities(ticket_id);
CREATE INDEX idx_ticket_activities_user_id ON ticket_activities(user_id);
CREATE INDEX idx_ticket_activities_created_at ON ticket_activities(created_at);

CREATE INDEX idx_sla_configurations_owner_id ON sla_configurations(owner_id);
CREATE INDEX idx_support_form_configs_owner_id ON support_form_configs(owner_id);

-- Insert default SLA configurations for all existing users
INSERT INTO sla_configurations (owner_id, priority, first_response_hours, resolution_hours, business_hours_only)
SELECT 
    u.id,
    'LOW',
    48,
    168, -- 7 days
    false
FROM app_users u
WHERE NOT EXISTS (
    SELECT 1 FROM sla_configurations s 
    WHERE s.owner_id = u.id AND s.priority = 'LOW'
);

INSERT INTO sla_configurations (owner_id, priority, first_response_hours, resolution_hours, business_hours_only)
SELECT 
    u.id,
    'MEDIUM',
    24,
    72, -- 3 days
    false
FROM app_users u
WHERE NOT EXISTS (
    SELECT 1 FROM sla_configurations s 
    WHERE s.owner_id = u.id AND s.priority = 'MEDIUM'
);

INSERT INTO sla_configurations (owner_id, priority, first_response_hours, resolution_hours, business_hours_only)
SELECT 
    u.id,
    'HIGH',
    8,
    24, -- 1 day
    false
FROM app_users u
WHERE NOT EXISTS (
    SELECT 1 FROM sla_configurations s 
    WHERE s.owner_id = u.id AND s.priority = 'HIGH'
);

INSERT INTO sla_configurations (owner_id, priority, first_response_hours, resolution_hours, business_hours_only)
SELECT 
    u.id,
    'URGENT',
    2,
    8,
    false
FROM app_users u
WHERE NOT EXISTS (
    SELECT 1 FROM sla_configurations s 
    WHERE s.owner_id = u.id AND s.priority = 'URGENT'
);

-- Insert default support form configurations for all existing users
INSERT INTO support_form_configs (owner_id, form_title, form_description, success_message, categories)
SELECT 
    u.id,
    'Get Support',
    'Need help? Fill out this form and our team will get back to you shortly.',
    'Thank you for contacting us! Your support request has been received and we will get back to you soon.',
    '["General", "Technical", "Billing", "Other"]'
FROM app_users u
WHERE NOT EXISTS (
    SELECT 1 FROM support_form_configs s 
    WHERE s.owner_id = u.id
);