-- V10011: Fix Contact waId Unique Constraint for Multi-Tenancy
--
-- Problem: The contacts table had a single-column UNIQUE constraint on wa_id alone.
-- This prevented two different tenants from having a customer with the same phone number,
-- which breaks the multi-tenant architecture.
--
-- Fix: Replace the single-column constraint with a composite unique constraint on
-- (wa_id, owner_id), so the same phone number can exist once per tenant.

-- Step 1: Drop the old single-column unique constraint on wa_id
-- PostgreSQL auto-names the constraint as <table>_<column>_key for @Column(unique=true)
ALTER TABLE contacts DROP CONSTRAINT IF EXISTS contacts_wa_id_key;
DROP INDEX IF EXISTS contacts_wa_id_key;

-- Step 2: Add the composite unique constraint on (wa_id, owner_id)
-- This enforces: same phone number is unique per tenant, not globally
ALTER TABLE contacts
    ADD CONSTRAINT uk_contact_waid_owner UNIQUE (wa_id, owner_id);
