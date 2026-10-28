-- Migration V11061: Hardened RAG chunk management, versioned staging, crawl jobs, and quotas

-- 1. Enhance document_chunks table with versioning, active flag, nullable index, and timestamp
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS chunk_index INT;
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS version INT DEFAULT 1 NOT NULL;
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS is_active BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();

-- 1b. Create knowledge_documents table if not exists with lifecycle status
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(512) NOT NULL,
    source_type VARCHAR(64) NOT NULL DEFAULT 'UPLOAD',
    source_url VARCHAR(2048),
    active_version INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    total_chunks INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_knowledge_doc_status CHECK (status IN ('PENDING', 'ACTIVE', 'DELETED', 'ARCHIVED'))
);

CREATE INDEX IF NOT EXISTS idx_knowledge_documents_tenant_status 
    ON knowledge_documents(tenant_id, status);

-- Clean up orphaned document_chunks whose tenant_id does not exist in tenants
DELETE FROM document_chunks 
WHERE tenant_id NOT IN (SELECT id FROM tenants);

-- Seed knowledge_documents from legacy document_chunks if records exist and tenant exists
WITH legacy_docs AS (
    SELECT 
        dc.document_id, 
        dc.tenant_id, 
        COALESCE(MAX(dc.metadata->>'source'), 'Document-' || SUBSTRING(dc.document_id::text, 1, 8)) AS name,
        COUNT(*) AS total_chunks,
        ROW_NUMBER() OVER (PARTITION BY dc.document_id ORDER BY COUNT(*) DESC) AS rn
    FROM document_chunks dc
    INNER JOIN tenants t ON t.id = dc.tenant_id
    GROUP BY dc.document_id, dc.tenant_id
)
INSERT INTO knowledge_documents (id, tenant_id, name, source_type, active_version, status, total_chunks, created_at, updated_at)
SELECT 
    document_id, 
    tenant_id, 
    name, 
    'UPLOAD', 
    1, 
    'ACTIVE', 
    total_chunks, 
    NOW(), 
    NOW()
FROM legacy_docs
WHERE rn = 1
ON CONFLICT (id) DO NOTHING;

-- 2. Stage 1 Backfill: Overflow-safe metadata extraction with bounded regex and deduplication
WITH ranked_meta AS (
    SELECT id, 
           ROW_NUMBER() OVER (
               PARTITION BY document_id, (metadata->>'chunk_index')::int 
               ORDER BY id ASC
           ) AS dup_rank,
           (metadata->>'chunk_index')::int AS parsed_idx
    FROM document_chunks
    WHERE metadata IS NOT NULL 
      AND metadata->>'chunk_index' ~ '^[0-9]{1,9}$'
)
UPDATE document_chunks dc
SET chunk_index = rm.parsed_idx
FROM ranked_meta rm
WHERE dc.id = rm.id AND rm.dup_rank = 1;

-- Stage 2 Backfill: Offset sequence starting strictly above MAX(chunk_index) per document
WITH max_per_doc AS (
    SELECT document_id, COALESCE(MAX(chunk_index), -1) AS current_max
    FROM document_chunks
    GROUP BY document_id
),
unmigrated_ranked AS (
    SELECT dc.id,
           COALESCE(mpd.current_max, -1) + ROW_NUMBER() OVER (
               PARTITION BY dc.document_id 
               ORDER BY dc.id ASC
           ) AS allocated_idx
    FROM document_chunks dc
    LEFT JOIN max_per_doc mpd ON dc.document_id = mpd.document_id
    WHERE dc.chunk_index IS NULL
)
UPDATE document_chunks dc
SET chunk_index = ur.allocated_idx,
    metadata = jsonb_set(COALESCE(dc.metadata, '{}'::jsonb), '{order_inferred}', 'true'::jsonb)
FROM unmigrated_ranked ur
WHERE dc.id = ur.id;

-- Stage 3: Verification assertion and NOT NULL constraint
DO $$
DECLARE
    duplicate_count INT;
BEGIN
    SELECT COUNT(*) INTO duplicate_count FROM (
        SELECT document_id, chunk_index 
        FROM document_chunks 
        GROUP BY document_id, chunk_index 
        HAVING COUNT(*) > 1
    ) dups;
    IF duplicate_count > 0 THEN
        RAISE EXCEPTION 'Migration V11061 failed: % duplicate (document_id, chunk_index) pairs detected', duplicate_count;
    END IF;
END $$;

ALTER TABLE document_chunks ALTER COLUMN chunk_index SET NOT NULL;

-- 3. Production indexes and single-active-version database constraint
CREATE INDEX IF NOT EXISTS idx_document_chunks_active_lookup 
    ON document_chunks(tenant_id, document_id, is_active, version);

CREATE INDEX IF NOT EXISTS idx_document_chunks_doc_version_idx 
    ON document_chunks(document_id, version, chunk_index);

-- Physical database constraint: Guarantees exactly one active chunk per (document, index)
CREATE UNIQUE INDEX IF NOT EXISTS idx_single_active_chunk_per_doc 
    ON document_chunks (document_id, chunk_index) 
    WHERE is_active = TRUE;

-- Authoritative dual deferred constraint triggers for full lifecycle and active-version invariant
CREATE OR REPLACE FUNCTION verify_document_chunks_active_invariant() 
RETURNS TRIGGER AS $$
DECLARE
    target_doc_id UUID;
    doc_status VARCHAR(32);
    doc_active_version INT;
    active_chunk_count INT;
    distinct_active_versions INT;
    actual_active_version INT;
BEGIN
    target_doc_id := COALESCE(NEW.document_id, OLD.document_id);
    IF target_doc_id IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT status, active_version 
    INTO doc_status, doc_active_version
    FROM knowledge_documents
    WHERE id = target_doc_id;

    -- If parent does not exist, or document is soft-deleted/archived, allow 0 active chunks
    IF doc_status IS NULL OR doc_status IN ('DELETED', 'ARCHIVED') THEN
        RETURN NULL;
    END IF;

    -- If document is in initial PENDING ingestion, allow 0 active chunks
    IF doc_status = 'PENDING' THEN
        SELECT COUNT(DISTINCT version) INTO distinct_active_versions
        FROM document_chunks WHERE document_id = target_doc_id AND is_active = TRUE;
        IF distinct_active_versions > 1 THEN
            RAISE EXCEPTION 'Invariant Violation: Pending document % has % active versions simultaneously!', 
                target_doc_id, distinct_active_versions;
        END IF;
        RETURN NULL;
    END IF;

    -- Strict invariants for ACTIVE documents:
    SELECT COUNT(*), COUNT(DISTINCT version), MIN(version)
    INTO active_chunk_count, distinct_active_versions, actual_active_version
    FROM document_chunks
    WHERE document_id = target_doc_id AND is_active = TRUE;

    IF active_chunk_count = 0 THEN
        RAISE EXCEPTION 'Invariant Violation: Active document % has 0 active chunks! Active documents must have chunks.', target_doc_id;
    END IF;

    IF distinct_active_versions != 1 THEN
        RAISE EXCEPTION 'Invariant Violation: Active document % has % distinct active versions!', 
            target_doc_id, distinct_active_versions;
    END IF;

    IF actual_active_version != doc_active_version THEN
        RAISE EXCEPTION 'Invariant Violation: Active document % pointer (%) does not match active chunk version (%)!',
            target_doc_id, doc_active_version, actual_active_version;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_verify_document_chunks_active_invariant ON document_chunks;
CREATE CONSTRAINT TRIGGER trg_verify_document_chunks_active_invariant
    AFTER INSERT OR UPDATE OR DELETE ON document_chunks
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION verify_document_chunks_active_invariant();

CREATE OR REPLACE FUNCTION verify_knowledge_document_active_invariant() 
RETURNS TRIGGER AS $$
DECLARE
    active_chunk_count INT;
    distinct_active_versions INT;
    actual_active_version INT;
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        SELECT COUNT(*), COUNT(DISTINCT version), MIN(version)
        INTO active_chunk_count, distinct_active_versions, actual_active_version
        FROM document_chunks
        WHERE document_id = NEW.id AND is_active = TRUE;

        IF active_chunk_count = 0 THEN
            RAISE EXCEPTION 'Invariant Violation: Cannot set document % to ACTIVE: 0 active chunks exist!', NEW.id;
        END IF;

        IF distinct_active_versions != 1 THEN
            RAISE EXCEPTION 'Invariant Violation: Document % has % distinct active versions!', NEW.id, distinct_active_versions;
        END IF;

        IF actual_active_version != NEW.active_version THEN
            RAISE EXCEPTION 'Invariant Violation: Document % pointer (%) does not match active chunk version (%)!',
                NEW.id, NEW.active_version, actual_active_version;
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_verify_knowledge_document_active_invariant ON knowledge_documents;
CREATE CONSTRAINT TRIGGER trg_verify_knowledge_document_active_invariant
    AFTER INSERT OR UPDATE OF active_version, status ON knowledge_documents
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION verify_knowledge_document_active_invariant();

-- 4. Create rag_crawl_jobs table
CREATE TABLE IF NOT EXISTS rag_crawl_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    source_url VARCHAR(2048) NOT NULL,
    crawl_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED', 
    idempotency_key VARCHAR(128),
    claim_generation INT NOT NULL DEFAULT 0, -- Monotonically increasing fencing token
    pages_discovered INT DEFAULT 0,
    pages_crawled INT DEFAULT 0,
    pages_failed INT DEFAULT 0,
    total_chunks INT DEFAULT 0,
    document_id UUID,
    document_name VARCHAR(512),
    partial_failures_json JSONB,
    error_message TEXT,
    locked_by VARCHAR(128),
    attempt_count INT DEFAULT 0,
    created_by VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    started_at TIMESTAMP WITH TIME ZONE,
    heartbeat_at TIMESTAMP WITH TIME ZONE,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_crawl_status CHECK (status IN ('QUEUED', 'CRAWLING', 'RETRYING', 'PARTIAL_SUCCESS', 'COMPLETED', 'CANCELLED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_rag_crawl_jobs_tenant_created 
    ON rag_crawl_jobs(tenant_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_crawl_idempotency 
    ON rag_crawl_jobs (tenant_id, idempotency_key) 
    WHERE idempotency_key IS NOT NULL AND status IN ('QUEUED', 'CRAWLING', 'RETRYING');

-- 5. Create rag_host_crawl_leases table for distributed monotonic slot rate limiting
CREATE TABLE IF NOT EXISTS rag_host_crawl_leases (
    host_name VARCHAR(255) PRIMARY KEY,
    next_permitted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 6. Create tenant_rag_quotas and rag_quota_reservations tables
CREATE TABLE IF NOT EXISTS tenant_rag_quotas (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    daily_page_limit INT NOT NULL DEFAULT 50,
    daily_pages_consumed INT NOT NULL DEFAULT 0,
    monthly_chunk_limit INT NOT NULL DEFAULT 2500,
    monthly_chunks_consumed INT NOT NULL DEFAULT 0,
    timezone VARCHAR(64) NOT NULL DEFAULT 'UTC',
    last_daily_reset_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_monthly_reset_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rag_quota_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    job_id UUID NOT NULL,
    pages_reserved INT NOT NULL,
    pages_consumed INT DEFAULT 0,
    chunks_reserved INT NOT NULL DEFAULT 0,
    chunks_consumed INT DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uk_quota_reservations_job_id UNIQUE (job_id),
    CONSTRAINT chk_reservation_status CHECK (status IN ('PENDING', 'COMMITTED', 'RELEASED', 'EXPIRED'))
);

CREATE INDEX IF NOT EXISTS idx_quota_reservations_tenant 
    ON rag_quota_reservations(tenant_id, status);
