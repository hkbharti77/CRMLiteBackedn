-- PostgreSQL vector domain alias for H2 test execution
CREATE DOMAIN IF NOT EXISTS vector AS TEXT;

-- ShedLock table: required for distributed scheduled task locking
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

CREATE TABLE IF NOT EXISTS faq_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    category VARCHAR(255),
    keywords VARCHAR(255),
    embedding TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    hit_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
