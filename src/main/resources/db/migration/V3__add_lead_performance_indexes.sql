-- Migration: Add performance indexes for multiple leads per contact feature
-- Version: V3
-- Description: Adds indexes to optimize lead queries for multiple leads per contact

-- Index for finding leads by contact ordered by creation date
-- Optimizes: findAllByContact, findTopByContactOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_leads_contact_created_at 
ON leads (contact_id, created_at DESC);

-- Index for finding active leads by contact and status
-- Optimizes: findTopByContactAndStatusNotInOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_leads_contact_status_created_at 
ON leads (contact_id, status, created_at DESC);

-- Index for owner-based queries (existing functionality)
-- Optimizes: findAllByOwner, findAllByStatusAndOwner
CREATE INDEX IF NOT EXISTS idx_leads_owner_status 
ON leads (owner_id, status);

-- Index for lead status queries
-- Optimizes: status-based filtering and reporting
CREATE INDEX IF NOT EXISTS idx_leads_status 
ON leads (status);

-- Composite index for owner and contact queries
-- Optimizes: owner-scoped contact lead queries
CREATE INDEX IF NOT EXISTS idx_leads_owner_contact 
ON leads (owner_id, contact_id);

-- Comments for index usage:
-- idx_leads_contact_created_at: Used when retrieving all leads for a contact in chronological order
-- idx_leads_contact_status_created_at: Used when finding active/inactive leads for a contact
-- idx_leads_owner_status: Used for pipeline views and status-based filtering
-- idx_leads_status: Used for global status reporting and analytics
-- idx_leads_owner_contact: Used for tenant-scoped contact lead operations