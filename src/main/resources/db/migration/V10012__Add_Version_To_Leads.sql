-- Add version column to leads table for JPA Optimistic Locking
ALTER TABLE leads ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
