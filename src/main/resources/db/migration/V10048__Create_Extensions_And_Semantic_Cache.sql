-- V10048__Create_Extensions_And_Semantic_Cache.sql
-- Production-level fallback for AI Database optimizations

-- 1. Create Extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Create semantic_cache table unconditionally
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
CREATE INDEX IF NOT EXISTS idx_semantic_cache_embedding_hnsw ON semantic_cache USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);

-- 3. Create document_chunks indexes
CREATE INDEX IF NOT EXISTS idx_chunks_content_trgm ON document_chunks USING gin (content gin_trgm_ops);

-- Ensure document_chunks embedding column has dimensions before creating hnsw index
ALTER TABLE document_chunks ALTER COLUMN embedding TYPE vector(384);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw ON document_chunks USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
