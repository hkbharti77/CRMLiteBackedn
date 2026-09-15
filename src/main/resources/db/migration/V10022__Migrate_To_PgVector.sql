-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Add a new vector column for embeddings
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding_vector vector(384);

-- Migrate existing JSONB embeddings to the new vector column
DO $$ 
BEGIN 
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='document_chunks' AND column_name='embedding' AND data_type='jsonb'
    ) THEN
        EXECUTE 'UPDATE document_chunks SET embedding_vector = (SELECT array_agg(value::text::real) FROM jsonb_array_elements(embedding))::vector WHERE embedding IS NOT NULL AND embedding_vector IS NULL';
    END IF;
END $$;

DO $$ 
BEGIN 
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='document_chunks' AND column_name='embedding_vector'
    ) THEN
        ALTER TABLE document_chunks DROP COLUMN IF EXISTS embedding;
        ALTER TABLE document_chunks RENAME COLUMN embedding_vector TO embedding;
    END IF;
END $$;

-- Create an HNSW index for fast Approximate Nearest Neighbor (ANN) search
-- Using vector_cosine_ops since cosine similarity is typically used for embeddings
CREATE INDEX IF NOT EXISTS document_chunks_embedding_hnsw_idx
ON document_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
