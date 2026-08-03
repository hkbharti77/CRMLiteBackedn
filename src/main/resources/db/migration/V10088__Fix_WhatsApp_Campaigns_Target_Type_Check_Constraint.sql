-- Migration V10088: Update target_type and status check constraints on whatsapp_campaigns table
-- Fixes PSQLException: new row for relation "whatsapp_campaigns" violates check constraint "whatsapp_campaigns_target_type_check"

ALTER TABLE whatsapp_campaigns DROP CONSTRAINT IF EXISTS whatsapp_campaigns_target_type_check;

ALTER TABLE whatsapp_campaigns ADD CONSTRAINT whatsapp_campaigns_target_type_check 
  CHECK (target_type IN ('ALL_CONTACTS', 'TAG_BASED', 'LEAD_STATUS_BASED', 'CSV_EXCEL_UPLOAD', 'CUSTOM_SEGMENT'));

ALTER TABLE whatsapp_campaigns DROP CONSTRAINT IF EXISTS whatsapp_campaigns_status_check;

ALTER TABLE whatsapp_campaigns ADD CONSTRAINT whatsapp_campaigns_status_check 
  CHECK (status IN ('DRAFT', 'PREVIEW', 'VALIDATING', 'SCHEDULED', 'QUEUED', 'RUNNING', 'PAUSED', 'FAILED', 'CANCELLED', 'COMPLETED'));
