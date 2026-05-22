-- ============================================================
-- V10027: Normalize lead_enquiries (AP-1 fix)
-- Replace Lead.enquiries TEXT blob with a relational table.
-- Backfills existing data using PL/pgSQL JSON parsing.
-- ============================================================

-- 1. Create the relational table
CREATE TABLE IF NOT EXISTS lead_enquiries (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    lead_id    UUID         NOT NULL,
    type       VARCHAR(50)  NOT NULL DEFAULT 'MANUAL',
    message    TEXT         NOT NULL,
    source     VARCHAR(255)          DEFAULT 'Manual Entry',
    status     VARCHAR(30)  NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ,

    CONSTRAINT pk_lead_enquiries PRIMARY KEY (id),
    CONSTRAINT fk_le_lead FOREIGN KEY (lead_id)
        REFERENCES leads(id) ON DELETE CASCADE,
    CONSTRAINT chk_le_type   CHECK (type   IN ('MANUAL','WHATSAPP','FORM','AUTO')),
    CONSTRAINT chk_le_status CHECK (status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_le_lead_id      ON lead_enquiries (lead_id);
CREATE INDEX IF NOT EXISTS idx_le_lead_created ON lead_enquiries (lead_id, created_at DESC);

-- 2. Backfill: parse existing JSON blobs into rows
-- Uses PL/pgSQL to iterate over every lead with non-empty enquiries
DO $$
DECLARE
    rec         RECORD;
    item        JSONB;
    enq_created TIMESTAMPTZ;
BEGIN
    FOR rec IN
        SELECT id, enquiries
        FROM leads
        WHERE enquiries IS NOT NULL
          AND enquiries <> '[]'
          AND enquiries <> ''
    LOOP
        BEGIN
            FOR item IN SELECT * FROM jsonb_array_elements(rec.enquiries::jsonb)
            LOOP
                -- Parse createdAt — fall back to now() if missing or malformed
                BEGIN
                    enq_created := (item->>'createdAt')::TIMESTAMPTZ;
                EXCEPTION WHEN OTHERS THEN
                    enq_created := now();
                END;

                INSERT INTO lead_enquiries (id, lead_id, type, message, source, status, created_at)
                VALUES (
                    COALESCE((item->>'id')::UUID, gen_random_uuid()),
                    rec.id,
                    COALESCE(NULLIF(item->>'type',   ''), 'MANUAL'),
                    COALESCE(NULLIF(item->>'message',''), '(no message)'),
                    COALESCE(NULLIF(item->>'source', ''), 'Manual Entry'),
                    COALESCE(NULLIF(item->>'status', ''), 'OPEN'),
                    COALESCE(enq_created, now())
                )
                ON CONFLICT (id) DO NOTHING;
            END LOOP;
        EXCEPTION WHEN OTHERS THEN
            -- Skip malformed JSON blobs — log to pg_log for investigation
            RAISE WARNING 'V10027: Failed to parse enquiries for lead %: %', rec.id, SQLERRM;
        END;
    END LOOP;
END $$;

-- 3. The old enquiries TEXT column is kept for now (backward compat).
-- It will be dropped in V10032 after full validation.
COMMENT ON COLUMN leads.enquiries
    IS 'DEPRECATED: Migrated to lead_enquiries table in V10027. Will be dropped in V10032.';
