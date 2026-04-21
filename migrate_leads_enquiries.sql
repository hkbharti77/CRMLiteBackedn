-- ============================================================
-- Multiple Leads Per Contact — Data Migration Script
-- Run this ONLY if migrating from a system where contacts
-- had a 1:1 lead relationship enforced at the application level.
-- ============================================================
-- IMPORTANT: Take a full database backup before running this script.
-- This script is SAFE to run on a system that already supports
-- multiple leads — it will detect and skip if not needed.
-- ============================================================

-- Step 1: Verify current state
DO $$
DECLARE
    total_leads     INTEGER;
    total_contacts  INTEGER;
    contacts_multi  INTEGER;
BEGIN
    SELECT COUNT(*) INTO total_leads    FROM leads;
    SELECT COUNT(*) INTO total_contacts FROM contacts;
    SELECT COUNT(*) INTO contacts_multi
    FROM (
        SELECT contact_id, COUNT(*) AS cnt
        FROM leads
        GROUP BY contact_id
        HAVING COUNT(*) > 1
    ) sub;

    RAISE NOTICE '=== Pre-migration state ===';
    RAISE NOTICE 'Total leads:                  %', total_leads;
    RAISE NOTICE 'Total contacts:               %', total_contacts;
    RAISE NOTICE 'Contacts with multiple leads: %', contacts_multi;
END $$;

-- Step 2: Ensure enquiries column exists and has correct default
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'leads' AND column_name = 'enquiries'
    ) THEN
        ALTER TABLE leads ADD COLUMN enquiries TEXT DEFAULT '[]';
        RAISE NOTICE 'Added enquiries column to leads table';
    ELSE
        RAISE NOTICE 'enquiries column already exists — skipping';
    END IF;
END $$;

-- Step 3: Backfill null enquiries with empty array
UPDATE leads
SET enquiries = '[]'
WHERE enquiries IS NULL;

-- Step 4: Ensure deleted flag exists and defaults to false
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'leads' AND column_name = 'deleted'
    ) THEN
        ALTER TABLE leads ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
        RAISE NOTICE 'Added deleted column to leads table';
    ELSE
        RAISE NOTICE 'deleted column already exists — skipping';
    END IF;
END $$;

-- Step 5: Ensure currency column exists and defaults to INR
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'leads' AND column_name = 'currency'
    ) THEN
        ALTER TABLE leads ADD COLUMN currency VARCHAR(10) DEFAULT 'INR';
        UPDATE leads SET currency = 'INR' WHERE currency IS NULL;
        RAISE NOTICE 'Added currency column to leads table';
    ELSE
        RAISE NOTICE 'currency column already exists — skipping';
    END IF;
END $$;

-- Step 6: Ensure payment_status column exists
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'leads' AND column_name = 'payment_status'
    ) THEN
        ALTER TABLE leads ADD COLUMN payment_status VARCHAR(20) DEFAULT 'NONE';
        UPDATE leads SET payment_status = 'NONE' WHERE payment_status IS NULL;
        RAISE NOTICE 'Added payment_status column to leads table';
    ELSE
        RAISE NOTICE 'payment_status column already exists — skipping';
    END IF;
END $$;

-- Step 7: Verify indexes exist (idempotent — IF NOT EXISTS)
CREATE INDEX IF NOT EXISTS idx_leads_contact_created_at
    ON leads (contact_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_leads_contact_status_created_at
    ON leads (contact_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_leads_owner_status
    ON leads (owner_id, status);

CREATE INDEX IF NOT EXISTS idx_leads_status
    ON leads (status);

CREATE INDEX IF NOT EXISTS idx_leads_owner_contact
    ON leads (owner_id, contact_id);

-- Step 8: Post-migration verification
DO $$
DECLARE
    null_enquiries  INTEGER;
    null_deleted    INTEGER;
    null_currency   INTEGER;
BEGIN
    SELECT COUNT(*) INTO null_enquiries  FROM leads WHERE enquiries IS NULL;
    SELECT COUNT(*) INTO null_deleted    FROM leads WHERE deleted IS NULL;
    SELECT COUNT(*) INTO null_currency   FROM leads WHERE currency IS NULL;

    RAISE NOTICE '=== Post-migration verification ===';
    RAISE NOTICE 'Leads with null enquiries:     % (expected: 0)', null_enquiries;
    RAISE NOTICE 'Leads with null deleted flag:  % (expected: 0)', null_deleted;
    RAISE NOTICE 'Leads with null currency:      % (expected: 0)', null_currency;

    IF null_enquiries > 0 OR null_deleted > 0 OR null_currency > 0 THEN
        RAISE EXCEPTION 'Migration verification failed — null values remain';
    END IF;

    RAISE NOTICE '=== Migration completed successfully ===';
END $$;
