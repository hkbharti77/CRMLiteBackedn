-- ============================================================
-- V10029: Normalize ticket categories (AP-3 fix)
-- Replaces SupportFormConfig.categories comma-string with a
-- proper relational table. Backfills from existing comma-strings.
-- ============================================================

-- 1. Create the ticket_categories table
CREATE TABLE IF NOT EXISTS ticket_categories (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    owner_id      UUID         NOT NULL,
    name          VARCHAR(100) NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_ticket_categories PRIMARY KEY (id),
    CONSTRAINT fk_tc_owner FOREIGN KEY (owner_id)
        REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT uk_tc_owner_name UNIQUE (owner_id, name)
);

CREATE INDEX IF NOT EXISTS idx_tc_owner_id ON ticket_categories (owner_id);

-- 2. Backfill categories from each tenant's comma-separated string
DO $$
DECLARE
    rec      RECORD;
    cat      TEXT;
    ord      INTEGER;
BEGIN
    FOR rec IN
        SELECT owner_id, categories
        FROM support_form_configs
        WHERE categories IS NOT NULL AND categories <> ''
    LOOP
        ord := 0;
        FOREACH cat IN ARRAY string_to_array(rec.categories, ',')
        LOOP
            cat := TRIM(cat);
            IF cat <> '' THEN
                INSERT INTO ticket_categories (owner_id, name, display_order)
                VALUES (rec.owner_id, cat, ord)
                ON CONFLICT (owner_id, name) DO NOTHING;
                ord := ord + 1;
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- 3. Add category_id FK to tickets (nullable — existing rows have no FK yet)
ALTER TABLE tickets
    ADD COLUMN IF NOT EXISTS category_id UUID
    REFERENCES ticket_categories(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_ticket_category_id ON tickets (category_id);

-- 4. Backfill category_id for existing tickets that have a category string
UPDATE tickets t
SET category_id = tc.id
FROM ticket_categories tc
WHERE tc.owner_id = t.owner_id
  AND LOWER(tc.name) = LOWER(t.category)
  AND t.category IS NOT NULL
  AND t.category_id IS NULL;

-- 5. Keep the old category VARCHAR column for backward compat.
-- It will be dropped in V10032 after full validation.
COMMENT ON COLUMN tickets.category
    IS 'DEPRECATED: Migrated to ticket_categories.id FK in V10029. Will be dropped in V10032.';
