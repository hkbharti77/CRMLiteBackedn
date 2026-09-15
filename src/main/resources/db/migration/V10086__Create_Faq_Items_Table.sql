-- V10086: FAQ items + vector column with fixed dimensions (idempotent)
-- Fixes: existing faq_items.embedding as plain "vector" (no dims) breaks HNSW.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS faq_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    category VARCHAR(100) DEFAULT 'General',
    keywords TEXT,
    embedding vector(384),
    is_active BOOLEAN DEFAULT TRUE,
    hit_count BIGINT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- If table already existed with undimensioned vector, fix typmod before HNSW
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_attribute a
        JOIN pg_class c ON a.attrelid = c.oid
        JOIN pg_namespace n ON c.relnamespace = n.oid
        JOIN pg_type t ON a.atttypid = t.oid
        WHERE n.nspname = 'public'
          AND c.relname = 'faq_items'
          AND a.attname = 'embedding'
          AND NOT a.attisdropped
          AND t.typname = 'vector'
          AND a.atttypmod < 0
    ) THEN
        DROP INDEX IF EXISTS idx_faq_items_embedding_hnsw;
        ALTER TABLE faq_items
            ALTER COLUMN embedding TYPE vector(384)
            USING (
                CASE
                    WHEN embedding IS NULL THEN NULL
                    ELSE embedding::text::vector(384)
                END
            );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_faq_items_tenant ON faq_items (tenant_id);
CREATE INDEX IF NOT EXISTS idx_faq_items_tenant_active ON faq_items (tenant_id, is_active);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_attribute a
        JOIN pg_class c ON a.attrelid = c.oid
        JOIN pg_namespace n ON c.relnamespace = n.oid
        JOIN pg_type t ON a.atttypid = t.oid
        WHERE n.nspname = 'public'
          AND c.relname = 'faq_items'
          AND a.attname = 'embedding'
          AND NOT a.attisdropped
          AND t.typname = 'vector'
          AND a.atttypmod > 0
    ) AND NOT EXISTS (
        SELECT 1 FROM pg_class WHERE relname = 'idx_faq_items_embedding_hnsw'
    ) THEN
        CREATE INDEX idx_faq_items_embedding_hnsw
            ON faq_items USING hnsw (embedding vector_cosine_ops)
            WITH (m = 16, ef_construction = 64);
    END IF;
END $$;
