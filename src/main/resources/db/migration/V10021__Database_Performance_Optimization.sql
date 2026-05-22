-- ============================================================
-- V10021: Database Scaling & Performance Optimization
-- Partitioning, Indexing, and JSONB conversion
-- ============================================================

-- 1. Convert TEXT payload to JSONB in activity_logs
-- This allows for performant filtering inside the JSON payload
ALTER TABLE activity_logs 
ALTER COLUMN payload TYPE JSONB USING payload::JSONB;

-- 2. Create Compound Indexes for performant filtering by tenant
-- (owner_id, created_at) covers most dashboard and timeline queries
CREATE INDEX idx_activity_owner_created ON activity_logs (owner_id, created_at DESC);

-- (owner_id, contact_id) covers contact-specific history
CREATE INDEX idx_activity_owner_contact ON activity_logs (owner_id, contact_id);

-- 3. Optimization for chat_messages
-- Add owner_id to chat_messages if it's missing (it currently relies on contact -> owner)
-- Actually, for partitioning, we need the partition key in the table.
-- Let's first check if owner_id exists in chat_messages. 
-- Based on the model it doesn't. We should add it to support tenant-level scaling.

ALTER TABLE chat_messages ADD COLUMN owner_id UUID REFERENCES app_users(id);

-- Fill existing owner_id from contact association
UPDATE chat_messages m
SET owner_id = c.owner_id
FROM contacts c
WHERE m.contact_id = c.id;

ALTER TABLE chat_messages ALTER COLUMN owner_id SET NOT NULL;

-- Compound index for fast chat retrieval
CREATE INDEX idx_chat_owner_timestamp ON chat_messages (owner_id, timestamp DESC);

-- 4. Create Archive Tables for Cold Storage Strategy
-- This prevents the main tables from growing unbounded while keeping history available.

CREATE TABLE activity_logs_archive (LIKE activity_logs INCLUDING ALL);
CREATE TABLE chat_messages_archive (LIKE chat_messages INCLUDING ALL);
CREATE TABLE processed_messages_archive (LIKE processed_messages INCLUDING ALL);

-- 5. Partitioning Strategy (Conceptual for now, as PostgreSQL requires 
-- defining partitions at table creation or using specific migration patterns)
-- For existing tables, we will use a "Hot/Cold" strategy where old data is moved 
-- to archive tables by the RetentionService.

-- Add a hint for future partitioning
COMMENT ON TABLE chat_messages IS 'Candidate for monthly RANGE partitioning on timestamp';
COMMENT ON TABLE activity_logs IS 'Candidate for monthly RANGE partitioning on created_at';
