-- Add Google OAuth fields to app_users
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS google_access_token VARCHAR(2048);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS google_refresh_token VARCHAR(2048);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS google_token_expiry TIMESTAMP;
