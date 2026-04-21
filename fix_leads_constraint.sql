-- Fix leads_status_check constraint to include BOOKED status
-- Run this against chatcrmdb

ALTER TABLE leads DROP CONSTRAINT IF EXISTS leads_status_check;

ALTER TABLE leads
    ADD CONSTRAINT leads_status_check
    CHECK (status IN ('NEW', 'INTERESTED', 'FOLLOW_UP', 'BOOKED', 'CLOSED_WON', 'CLOSED_LOST'));
