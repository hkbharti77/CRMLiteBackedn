-- Migration V11062: Drop legacy unique index on document_chunks(tenant_id, content_hash)
-- The legacy constraint document_chunks_hash_tenant_unique_idx prevented staged re-indexing
-- because candidate chunks (version N+1, is_active=false) share content_hash with active chunks (version N).
-- Invariant integrity is now maintained by idx_single_active_chunk_per_doc (document_id, chunk_index WHERE is_active=TRUE).

DROP INDEX IF EXISTS document_chunks_hash_tenant_unique_idx;

-- Ensure standard non-unique index exists for tenant-hash lookups
CREATE INDEX IF NOT EXISTS idx_document_chunks_tenant_hash ON document_chunks (tenant_id, content_hash);

