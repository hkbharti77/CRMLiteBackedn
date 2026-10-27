-- Migration V11058: Create Credit Reservation Table, Idempotency on WebChatMessages, and Hallucination Audit Logs
-- Supports Phase 2: Two-Phase Credit Protocol, Durable Persistence, and Advisory Hallucination Verification

-- 1. Two-Phase Credit Reservation Table
CREATE TABLE IF NOT EXISTS tenant_credit_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    amount BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    reference_id VARCHAR(100),
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    settled_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_credit_reservations_tenant_status 
ON tenant_credit_reservations(tenant_id, status);

CREATE INDEX IF NOT EXISTS idx_credit_reservations_expires 
ON tenant_credit_reservations(status, expires_at);

-- 2. Idempotency Key for Web Chat Messages (Durable Redis Stream Persistence)
ALTER TABLE web_chat_messages 
ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS idx_web_chat_messages_idempotency_key 
ON web_chat_messages(idempotency_key) 
WHERE idempotency_key IS NOT NULL;

-- 3. Hallucination Audit Logs (Decoupled Semantic Verification)
CREATE TABLE IF NOT EXISTS hallucination_audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID REFERENCES tenants(id) ON DELETE CASCADE,
    query TEXT,
    response TEXT,
    context_sample TEXT,
    result VARCHAR(50) NOT NULL,
    reason TEXT,
    latency_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_hallucination_audit_tenant_date 
ON hallucination_audit_logs(tenant_id, created_at DESC);
