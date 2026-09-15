-- V11013: Add index for exact FAQ question lookup
CREATE INDEX IF NOT EXISTS idx_faq_items_tenant_question_lower 
ON faq_items (tenant_id, lower(trim(question))) 
WHERE is_active = true;
