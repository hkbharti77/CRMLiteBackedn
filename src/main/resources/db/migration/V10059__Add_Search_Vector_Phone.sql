-- V10059: Update FTS search_vector to include submitter_phone on tickets table

-- 1. Drop existing index that depends on search_vector
DROP INDEX IF EXISTS idx_tickets_search_vector;

-- 2. Drop existing generated column
ALTER TABLE tickets DROP COLUMN IF EXISTS search_vector;

-- 3. Re-create search_vector column including submitter_phone
ALTER TABLE tickets
    ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        to_tsvector('english',
            COALESCE(subject,         '') || ' ' ||
            COALESCE(submitter_name,  '') || ' ' ||
            COALESCE(submitter_email, '') || ' ' ||
            COALESCE(submitter_phone, '') || ' ' ||
            COALESCE(category,        '')
        )
    ) STORED;

-- 4. Re-create GIN index for search_vector
CREATE INDEX IF NOT EXISTS idx_tickets_search_vector
    ON tickets USING GIN (search_vector);
