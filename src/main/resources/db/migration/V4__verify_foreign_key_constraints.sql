-- Ensure leads table exists before creating performance indexes on fresh database
CREATE TABLE IF NOT EXISTS leads (
    id UUID PRIMARY KEY,
    contact_id UUID,
    owner_id UUID,
    status VARCHAR(255),
    created_at TIMESTAMP
);

-- Index for finding active leads by contact and status
-- Optimizes: findTopByContactAndStatusNotInOrderByCreatedAtDesc
CREATE INDEX IF NOT EXISTS idx_leads_contact_status_created_at
ON leads (contact_id, status, created_at DESC);

-- Composite index for owner and contact queries
-- Optimizes: owner-scoped contact lead queries
CREATE INDEX IF NOT EXISTS idx_leads_owner_contact
ON leads (owner_id, contact_id);
