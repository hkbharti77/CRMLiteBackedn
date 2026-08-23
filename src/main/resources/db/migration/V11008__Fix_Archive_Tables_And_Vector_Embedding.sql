-- V11008__Fix_Archive_Tables_And_Vector_Embedding.sql
-- Production fix: Safely convert document_chunks.embedding to vector(384) and ensure archive tables exist

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. Ensure Archive Tables Exist for DatabaseRetentionService
CREATE TABLE IF NOT EXISTS activity_logs_archive (LIKE activity_logs INCLUDING ALL);
CREATE TABLE IF NOT EXISTS chat_messages_archive (LIKE chat_messages INCLUDING ALL);
CREATE TABLE IF NOT EXISTS processed_messages_archive (LIKE processed_messages INCLUDING ALL);

-- 2. Convert document_chunks.embedding to vector(384) if it's currently jsonb or text
DO $$ 
BEGIN
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'document_chunks' 
          AND column_name = 'embedding'
          AND (data_type IN ('jsonb', 'text', 'ARRAY', 'character varying') OR udt_name != 'vector')
    ) THEN
        -- Add temporary vector column
        ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding_vec vector(384);

        -- Convert jsonb array to vector
        BEGIN
            UPDATE document_chunks
            SET embedding_vec = (
                SELECT array_agg(value::text::real)
                FROM jsonb_array_elements(embedding)
            )::vector
            WHERE embedding IS NOT NULL AND embedding_vec IS NULL AND pg_typeof(embedding)::text = 'jsonb';
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'JSONB array extraction to vector skipped or unneeded.';
        END;

        -- Convert text to vector if jsonb conversion didn't apply
        BEGIN
            UPDATE document_chunks
            SET embedding_vec = embedding::text::vector
            WHERE embedding IS NOT NULL AND embedding_vec IS NULL;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'Text cast to vector skipped or unneeded.';
        END;

        -- Drop old embedding column and rename embedding_vec
        ALTER TABLE document_chunks DROP COLUMN IF EXISTS embedding;
        ALTER TABLE document_chunks RENAME COLUMN embedding_vec TO embedding;
        ALTER TABLE document_chunks ALTER COLUMN embedding SET NOT NULL;
    END IF;
END $$;

-- 3. Recreate HNSW Index for vector search
DROP INDEX IF EXISTS idx_chunks_embedding_hnsw;
DROP INDEX IF EXISTS document_chunks_embedding_hnsw_idx;

CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw 
ON document_chunks 
USING hnsw (embedding vector_cosine_ops) 
WITH (m = 16, ef_construction = 64);
