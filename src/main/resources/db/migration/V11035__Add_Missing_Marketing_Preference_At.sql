-- Flyway Migration: V11035__Add_Missing_Marketing_Preference_At.sql
-- Adds marketing_preference_at column that was added to V11034 after it was first applied.
-- Uses IF NOT EXISTS so this is safe to run even if it was never missing.

ALTER TABLE contacts
    ADD COLUMN IF NOT EXISTS marketing_preference_at TIMESTAMPTZ;
