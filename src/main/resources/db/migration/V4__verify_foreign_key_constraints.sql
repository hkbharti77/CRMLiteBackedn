-- Migration: Add performance indexes for leads (contact_id, status)
-- Version: V4
-- Description: Safe index additions only — no FK constraint changes (JPA manages those)

-- Index for finding active leads by contact and status
-- Optimizes: findTopByContactAndStatusNotInOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_leads_contact_status_created_at
ON leads (contact_id, status, created_at DESC);

-- Composite index for owner and contact queries
-- Optimizes: owner-scoped contact lead queries
CREATE INDEX IF NOT EXISTS idx_leads_owner_contact
ON leads (owner_id, contact_id);
