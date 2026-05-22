-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Add a new vector column for embeddings
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS embedding_vector vector(384);

-- Migrate existing JSONB embeddings to the new vector column
-- The old format is a JSON array of floats
UPDATE document_chunks
SET embedding_vector = (
    SELECT array_agg(value::text::real)
    FROM jsonb_array_elements(embedding)
)::vector
WHERE embedding IS NOT NULL AND embedding_vector IS NULL;

-- Drop the old jsonb column (only if it's still a jsonb type)
ALTER TABLE document_chunks DROP COLUMN IF EXISTS embedding;

-- Rename the new vector column to embedding
ALTER TABLE document_chunks RENAME COLUMN embedding_vector TO embedding;

-- Enforce NOT NULL on the new embedding column
ALTER TABLE document_chunks ALTER COLUMN embedding SET NOT NULL;

-- Create an HNSW index for fast Approximate Nearest Neighbor (ANN) search
-- Using vector_cosine_ops since cosine similarity is typically used for embeddings
CREATE INDEX IF NOT EXISTS document_chunks_embedding_hnsw_idx
ON document_chunks
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);
