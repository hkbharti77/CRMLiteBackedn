-- V10081: Fix app_users_role_check constraint
-- Drops the old constraint (which only allowed OWNER/ADMIN/AGENT) and recreates it
-- with the correct set of roles. PLATFORM_ADMIN is NOT included here — platform admins
-- authenticate via the separate platform_admin table and PlatformAuthFilter, not app_users.

-- Migrate any stale PLATFORM_ADMIN rows to SUPER_ADMIN (safety net)
UPDATE app_users SET role = 'SUPER_ADMIN' WHERE role = 'PLATFORM_ADMIN';

ALTER TABLE app_users
    DROP CONSTRAINT IF EXISTS app_users_role_check;

ALTER TABLE app_users
    ADD CONSTRAINT app_users_role_check
    CHECK (role IN ('OWNER', 'ADMIN', 'AGENT', 'SUPER_ADMIN'));
