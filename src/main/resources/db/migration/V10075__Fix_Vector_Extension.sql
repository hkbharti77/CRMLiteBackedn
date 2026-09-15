CREATE EXTENSION IF NOT EXISTS vector;

DO $$ 
BEGIN
    IF EXISTS (
        SELECT 1 
        FROM information_schema.columns 
        WHERE table_name = 'document_chunks' 
          AND column_name = 'embedding'
    ) THEN
        BEGIN
            ALTER TABLE document_chunks ALTER COLUMN embedding TYPE vector(384) USING embedding::text::vector;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'Skipping alter of document_chunks.embedding: already vector(384) or alter failed.';
        END;
    END IF;
END $$;