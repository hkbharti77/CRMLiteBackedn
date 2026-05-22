-- ============================================================
-- V10026: Persist SupportFormConfig config fields (AP-4 fix)
-- These 7 fields were @Transient in Java — never persisted.
-- Every server restart silently reset them to hardcoded defaults.
-- ============================================================

ALTER TABLE support_form_configs
    ADD COLUMN IF NOT EXISTS phone_required              BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS category_required           BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS rate_limit_enabled          BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS duplicate_detection_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS default_priority            VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    ADD COLUMN IF NOT EXISTS primary_color               VARCHAR(20)          DEFAULT '#667eea',
    ADD COLUMN IF NOT EXISTS logo_url                    VARCHAR(500);

-- Ensure default_priority only contains valid values
ALTER TABLE support_form_configs
    DROP CONSTRAINT IF EXISTS chk_support_form_priority;

ALTER TABLE support_form_configs
    ADD CONSTRAINT chk_support_form_priority
    CHECK (default_priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT'));

COMMENT ON COLUMN support_form_configs.phone_required
    IS 'Whether the support form requires a phone number field';
COMMENT ON COLUMN support_form_configs.category_required
    IS 'Whether the user must select a category before submitting';
COMMENT ON COLUMN support_form_configs.rate_limit_enabled
    IS 'Enable per-IP rate limiting on the public support form submission endpoint';
COMMENT ON COLUMN support_form_configs.duplicate_detection_enabled
    IS 'Enable duplicate ticket detection (same email + subject within 1 hour)';
