CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE document_chunks ALTER COLUMN embedding TYPE vector USING embedding::text::vector;