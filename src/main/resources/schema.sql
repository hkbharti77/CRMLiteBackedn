CREATE EXTENSION IF NOT EXISTS vector;
DROP TABLE IF EXISTS document_chunks CASCADE;

-- ShedLock table for distributed scheduled task locking
CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
