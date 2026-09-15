-- V10077: Add bot_paused to contacts table

ALTER TABLE contacts
ADD COLUMN IF NOT EXISTS bot_paused BOOLEAN NOT NULL DEFAULT false;
