-- Manual fix for PostgreSQL Error: column l1_0.deleted does not exist
-- Run this in your database console if Hibernate fails to update the schema automatically.
ALTER TABLE leads ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE;
