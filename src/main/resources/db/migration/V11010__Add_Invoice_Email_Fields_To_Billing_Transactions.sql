-- Flyway Migration: Add invoice email status and timestamp to billing_transactions table

ALTER TABLE billing_transactions 
ADD COLUMN IF NOT EXISTS invoice_email_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
ADD COLUMN IF NOT EXISTS invoice_email_sent_at TIMESTAMP;
