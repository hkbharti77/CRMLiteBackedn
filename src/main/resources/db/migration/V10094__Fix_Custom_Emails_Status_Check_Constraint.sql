-- Migration V10094: Fix custom_emails status check constraint to include all campaign states
-- Fixes PSQLException: new row for relation "custom_emails" violates check constraint "custom_emails_status_check"

ALTER TABLE custom_emails DROP CONSTRAINT IF EXISTS custom_emails_status_check;

ALTER TABLE custom_emails ADD CONSTRAINT custom_emails_status_check 
  CHECK (status IN ('DRAFT', 'SCHEDULED', 'SENDING', 'PAUSED', 'CANCELLED', 'COMPLETED', 'FAILED', 'SENT'));
