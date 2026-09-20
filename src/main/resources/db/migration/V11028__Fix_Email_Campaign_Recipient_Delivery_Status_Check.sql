-- V11028: Add SENDING to email_campaign_recipient delivery_status CHECK constraint
-- The Java enum DeliveryStatus includes SENDING but the DB constraint was missing it,
-- causing campaign execution to fail with constraint violation.

DO $$
BEGIN
    EXECUTE 'ALTER TABLE email_campaign_recipient DROP CONSTRAINT IF EXISTS email_campaign_recipient_delivery_status_check';
END $$;

ALTER TABLE email_campaign_recipient ADD CONSTRAINT email_campaign_recipient_delivery_status_check
    CHECK (delivery_status IN ('PENDING', 'SENDING', 'SENT', 'DELIVERED', 'BOUNCED', 'FAILED'));
