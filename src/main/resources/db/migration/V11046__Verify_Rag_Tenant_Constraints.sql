-- Migration V11046: Verify and Harden RAG Entity Tenant Isolation Constraints
-- Description: Ensures strict not-null constraints and performance indexes on document_chunks and faq_items.

DO $$
BEGIN
    -- Ensure tenant_id is NOT NULL on document_chunks
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'document_chunks' AND column_name = 'tenant_id' AND is_nullable = 'YES'
    ) THEN
        ALTER TABLE document_chunks ALTER COLUMN tenant_id SET NOT NULL;
    END IF;

    -- Ensure tenant_id is NOT NULL on faq_items
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'faq_items' AND column_name = 'tenant_id' AND is_nullable = 'YES'
    ) THEN
        ALTER TABLE faq_items ALTER COLUMN tenant_id SET NOT NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_document_chunks_tenant_id ON document_chunks(tenant_id);
CREATE INDEX IF NOT EXISTS idx_faq_items_tenant_id ON faq_items(tenant_id);
