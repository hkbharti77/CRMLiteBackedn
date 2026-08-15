-- Migration: V10082__Create_Platform_Settings_And_Niche_Templates.sql
-- Description: Create platform_settings and niche_templates tables for Enterprise Super Admin features

-- 1. Platform Settings Table
CREATE TABLE IF NOT EXISTS platform_settings (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(255)
);

-- Seed default platform settings
INSERT INTO platform_settings (setting_key, setting_value)
VALUES 
('platform_name', 'CRMLite'),
('platform_url', 'https://app.crmlite.io'),
('support_email', 'support@crmlite.io'),
('default_plan', 'starter'),
('trial_days', '14'),
('max_leads_starter', '500'),
('enforce_2fa', 'false'),
('password_policy', 'strict'),
('session_timeout', '30'),
('ip_whitelist', '["103.21.45.10", "103.21.45.11"]'),
('feature_flags', '{"whatsapp": true, "emailCampaigns": true, "aiSuggestions": true, "calendarSync": true, "leadScoring": true, "customDomains": false, "apiAccess": true, "sso": false}')
ON CONFLICT (setting_key) DO NOTHING;

-- 2. Niche Templates Table
CREATE TABLE IF NOT EXISTS niche_templates (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    niche VARCHAR(100) NOT NULL,
    icon VARCHAR(50) NOT NULL,
    color VARCHAR(30) NOT NULL,
    description TEXT,
    stages TEXT NOT NULL, -- JSON array of stage names
    status VARCHAR(30) DEFAULT 'published',
    tenants_using INT DEFAULT 0,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Seed default niche templates
INSERT INTO niche_templates (id, name, niche, icon, color, description, stages, status, tenants_using)
VALUES 
('tmpl-luxury-realestate', 'Luxury Real Estate', 'Real Estate', 'crown', '#7C3AED', 'Tailored for high-end residential sales with private viewings and escrow tracking.', '["New Lead", "Pre-Qualified", "Private Viewing", "Offer Made", "Escrow", "Closed Won"]', 'published', 14),
('tmpl-rental-property', 'Rental Property Management', 'Property Management', 'key', '#2563EB', 'Streamlines tenant screening, lease agreements, and security deposit handling.', '["Inquiry", "Screening", "Property Tour", "Application", "Lease Signed", "Moved In"]', 'published', 22),
('tmpl-commercial-re', 'Commercial Real Estate', 'Commercial', 'building', '#10B981', 'Complex deal cycles with multi-party negotiations and LOI milestones.', '["Prospect", "LOI Sent", "Due Diligence", "Board Approval", "Contract Signed", "Closed"]', 'published', 8),
('tmpl-dental-clinic', 'Dental & Medical Practice', 'Healthcare', 'palmtree', '#06B6D4', 'Patient intake, consultation scheduling, and treatment plan follow-ups.', '["New Inquiry", "Consultation", "Treatment Plan", "Scheduled", "Completed"]', 'published', 11),
('tmpl-legal-services', 'Legal Services & Law Firm', 'Legal', 'map', '#F59E0B', 'Case evaluation, conflict checks, retainer agreements, and court hearings.', '["Case Lead", "Conflict Check", "Retainer Sent", "Active Case", "Resolved"]', 'published', 5),
('tmpl-ecommerce-sales', 'E-Commerce B2B Wholesale', 'E-Commerce', 'trending', '#EC4899', 'High-volume sample requests, wholesale pricing quotes, and re-orders.', '["Sample Requested", "Quote Sent", "Sample Approved", "PO Received", "Fulfilled"]', 'published', 18)
ON CONFLICT (id) DO NOTHING;
