-- Migration: Google Audit Logs, Dead Letter Queue & Multi-Tenant Isolation
-- Supports Phase 13 (Async Worker & Dead Letter), Phase 14 (Google API Observability) & Enterprise Tenant Isolation

-- ── 1. Tenant columns on Connections and Devices ─────────────────────────────
ALTER TABLE google_connections ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_gconn_tenant_id ON google_connections(tenant_id);

ALTER TABLE user_devices ADD COLUMN IF NOT EXISTS tenant_id UUID;
CREATE INDEX IF NOT EXISTS idx_udev_tenant_id ON user_devices(tenant_id);

-- ── 2. Audit logs with tenant isolation ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS google_api_audit_logs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID,
    user_id       UUID,
    connection_id UUID REFERENCES google_connections(id) ON DELETE SET NULL,
    integration   VARCHAR(50),
    operation     VARCHAR(100),
    google_api    VARCHAR(100),
    request_id    VARCHAR(100),
    status        VARCHAR(30),
    latency_ms    BIGINT,
    error_code    VARCHAR(50),
    error_message TEXT,
    retry_count   INT DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_gaudit_tenant_id ON google_api_audit_logs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_gaudit_user_id ON google_api_audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_gaudit_integration ON google_api_audit_logs(integration);
CREATE INDEX IF NOT EXISTS idx_gaudit_status ON google_api_audit_logs(status);
CREATE INDEX IF NOT EXISTS idx_gaudit_created_at ON google_api_audit_logs(created_at);

-- ── 3. Dead letter queue with tenant isolation ────────────────────────────────
CREATE TABLE IF NOT EXISTS google_dead_letters (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID,
    user_id         UUID NOT NULL,
    connection_id   UUID REFERENCES google_connections(id) ON DELETE SET NULL,
    resource_type   VARCHAR(50) NOT NULL,
    crm_resource_id UUID,
    operation       VARCHAR(100) NOT NULL,
    payload_json    TEXT,
    error_message   TEXT,
    retry_count     INT DEFAULT 0,
    last_attempt_at TIMESTAMP DEFAULT now(),
    resolved        BOOLEAN DEFAULT FALSE,
    resolved_at     TIMESTAMP,
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_gdl_tenant_id ON google_dead_letters(tenant_id);
CREATE INDEX IF NOT EXISTS idx_gdl_user_id ON google_dead_letters(user_id);
CREATE INDEX IF NOT EXISTS idx_gdl_resolved ON google_dead_letters(resolved);
CREATE INDEX IF NOT EXISTS idx_gdl_created_at ON google_dead_letters(created_at);
