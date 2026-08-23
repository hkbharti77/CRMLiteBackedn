-- V11007__Fix_Document_Chunks_Embedding_Vector.sql
-- Production fix: Migrate document_chunks.embedding column to vector(384)

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DO $$ 
BEGIN
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'document_chunks' 
          AND column_name = 'embedding' 
          AND data_type IN ('jsonb', 'text', 'USER-DEFINED', 'ARRAY', 'character varying')
    ) THEN
        BEGIN
            ALTER TABLE document_chunks ALTER COLUMN embedding TYPE vector(384) USING embedding::text::vector;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'Direct alter of embedding column failed or already vector with correct dimension.';
        END;
    END IF;
END $$;

DROP INDEX IF EXISTS idx_chunks_embedding_hnsw;
DROP INDEX IF EXISTS document_chunks_embedding_hnsw_idx;

CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw 
ON document_chunks 
USING hnsw (embedding vector_cosine_ops) 
WITH (m = 16, ef_construction = 64);
