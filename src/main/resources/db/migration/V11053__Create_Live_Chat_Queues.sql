-- =====================================================================
-- Migration V11053: Create Live Chat Queues Table
-- =====================================================================
-- This table was missing a Flyway migration. The JPA entity LiveChatQueue
-- references this table for live chat SLA queue management.
-- Note: FK constraints omitted — table may be applied to a DB where parent
-- tables are already seeded via baseline; JPA handles relational integrity.

CREATE TABLE IF NOT EXISTS live_chat_queues (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    contact_id      UUID         NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'QUEUED',
    queued_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    sla_expires_at  TIMESTAMP    NOT NULL,
    sla_breached    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_live_chat_queues PRIMARY KEY (id),
    CONSTRAINT live_chat_queues_status_check CHECK (status IN ('QUEUED', 'ASSIGNED', 'EXPIRED', 'CANCELLED'))
);

-- Indexes (matching @Index annotations on LiveChatQueue entity)
CREATE INDEX IF NOT EXISTS idx_queue_tenant_status_queued ON live_chat_queues (tenant_id, status, queued_at);
CREATE INDEX IF NOT EXISTS idx_queue_sla                  ON live_chat_queues (sla_breached, sla_expires_at);

