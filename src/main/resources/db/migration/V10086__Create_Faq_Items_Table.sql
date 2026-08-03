-- V10086__Create_Faq_Items_Table.sql
-- High-Performance Enterprise FAQ Store with Vector Embeddings

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

CREATE INDEX IF NOT EXISTS idx_faq_items_tenant ON faq_items (tenant_id);
CREATE INDEX IF NOT EXISTS idx_faq_items_tenant_active ON faq_items (tenant_id, is_active);
CREATE INDEX IF NOT EXISTS idx_faq_items_embedding_hnsw ON faq_items USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
