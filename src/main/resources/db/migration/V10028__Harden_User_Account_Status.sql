-- ============================================================
-- V10028: Harden User.accountStatus (AP-6 fix)
-- Convert raw VARCHAR to constrained column with CHECK.
-- Normalizes casing ('active' → 'ACTIVE') and drops invalid values.
-- ============================================================

-- 1. Normalize any legacy casing variants
UPDATE app_users
SET account_status = UPPER(account_status)
WHERE account_status IS NOT NULL;

-- 2. Replace any unknown values with ACTIVE (safe default)
UPDATE app_users
SET account_status = 'ACTIVE'
WHERE account_status NOT IN ('ACTIVE', 'LOCKED', 'SUSPENDED')
   OR account_status IS NULL;

-- 3. Set NOT NULL with default
ALTER TABLE app_users
    ALTER COLUMN account_status SET NOT NULL,
    ALTER COLUMN account_status SET DEFAULT 'ACTIVE';

-- 4. Add CHECK constraint — now that data is clean
ALTER TABLE app_users
    DROP CONSTRAINT IF EXISTS chk_user_account_status;

ALTER TABLE app_users
    ADD CONSTRAINT chk_user_account_status
    CHECK (account_status IN ('ACTIVE', 'LOCKED', 'SUSPENDED'));

COMMENT ON COLUMN app_users.account_status
    IS 'ACTIVE | LOCKED (too many failed logins) | SUSPENDED (admin action)';
