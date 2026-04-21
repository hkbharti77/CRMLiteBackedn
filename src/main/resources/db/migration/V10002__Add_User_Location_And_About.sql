-- Add about_us, latitude, and longitude columns to app_users table
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS about_us TEXT;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
