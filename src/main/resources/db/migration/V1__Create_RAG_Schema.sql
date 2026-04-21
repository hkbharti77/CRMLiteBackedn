-- V1: Create Multi-Tenant RAG Schema
-- NOTE: pg_trgm and pgcrypto extensions must be created ONCE manually by a superuser:
--   psql -U postgres -d <your_db> -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
--   psql -U postgres -d <your_db> -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;"

-- 1. Create document_chunks table
CREATE TABLE IF NOT EXISTS document_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    embedding JSONB NOT NULL, -- Stored as JSONB array mapped from float[]
    metadata JSONB,
    CONSTRAINT content_not_empty CHECK (length(content) > 0),
    CONSTRAINT content_length_limit CHECK (length(content) <= 2000)
);

-- 2. Unique Hash Index (Tenant-scoped deduplication)
CREATE UNIQUE INDEX IF NOT EXISTS document_chunks_hash_tenant_unique_idx ON document_chunks (tenant_id, content_hash);

-- 3. Composite ID Index (Fast Lifecycle Operations)
CREATE INDEX IF NOT EXISTS document_chunks_doc_tenant_idx ON document_chunks (document_id, tenant_id);

-- 4. B-Tree Index for Tenant filtering (Crucial for isolation)
CREATE INDEX IF NOT EXISTS document_chunks_tenant_idx ON document_chunks (tenant_id);

-- 6. Standard text index (pg_trgm GIN index can be added manually by superuser later)
CREATE INDEX IF NOT EXISTS document_chunks_content_idx ON document_chunks (tenant_id, content_hash);

-- 7. Production Performance Tuning (Autovacuum & Fillfactor)
ALTER TABLE document_chunks SET (
  autovacuum_vacuum_scale_factor = 0.02,
  autovacuum_analyze_scale_factor = 0.01,
  fillfactor = 90
);
