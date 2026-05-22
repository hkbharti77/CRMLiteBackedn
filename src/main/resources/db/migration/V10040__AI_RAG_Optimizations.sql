-- V10040__AI_RAG_Optimizations.sql
-- Senior AI Systems Engineer Optimization Pack

-- 1. Ensure extensions are available (Defensive check)
DO $$ 
BEGIN
    BEGIN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
    EXCEPTION WHEN OTHERS THEN 
        RAISE NOTICE 'pg_trgm extension could not be created/verified.';
    END;
    
    BEGIN
        CREATE EXTENSION IF NOT EXISTS vector;
    EXCEPTION WHEN OTHERS THEN 
        RAISE NOTICE 'vector extension is not available on this server.';
    END;
END $$;

-- 2. Create Semantic Cache table (Only if vector extension exists)
DO $$ 
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        CREATE TABLE IF NOT EXISTS semantic_cache (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            tenant_id UUID NOT NULL,
            query_text TEXT NOT NULL,
            embedding vector(384) NOT NULL,
            response_text TEXT NOT NULL,
            created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
            last_accessed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
        );
        
        CREATE INDEX IF NOT EXISTS idx_semantic_cache_tenant ON semantic_cache (tenant_id);
        CREATE INDEX IF NOT EXISTS idx_semantic_cache_embedding_hnsw ON semantic_cache USING hnsw (embedding vector_cosine_ops)
        WITH (m = 16, ef_construction = 64);
    END IF;
END $$;

-- 3. Indexes for Document Chunks (Only if extensions exist)
DO $$ 
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_chunks_content_trgm ON document_chunks USING gin (content gin_trgm_ops);
    END IF;

    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector') THEN
        DROP INDEX IF EXISTS idx_chunks_embedding_ivf;
        CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops)
        WITH (m = 16, ef_construction = 64);
    END IF;
END $$;

-- 5. Cleanup policy for semantic cache (LRU helper)
CREATE OR REPLACE FUNCTION purge_old_semantic_cache_entries() RETURNS void AS $$
BEGIN
    -- Keep only top 1000 entries per tenant based on last access
    DELETE FROM semantic_cache
    WHERE id IN (
        SELECT id FROM (
            SELECT id, ROW_NUMBER() OVER (PARTITION BY tenant_id ORDER BY last_accessed_at DESC) as rn
            FROM semantic_cache
        ) t
        WHERE t.rn > 1000
    );
END;
$$ LANGUAGE plpgsql;
