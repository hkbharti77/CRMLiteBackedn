-- Migration V11060: Create Contact Merge and Deduplication Schema
-- Adds soft-merge tracking columns to contacts table and creates immutable audit ledger for contact merges.

ALTER TABLE contacts ADD COLUMN IF NOT EXISTS is_merged BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE contacts ADD COLUMN IF NOT EXISTS merged_into_id UUID REFERENCES contacts(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_contacts_tenant_is_merged ON contacts(tenant_id, is_merged);
CREATE INDEX IF NOT EXISTS idx_contacts_merged_into ON contacts(merged_into_id);

CREATE TABLE IF NOT EXISTS contact_merge_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    primary_contact_id UUID NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    secondary_contact_id UUID NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    merged_by_user_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    merged_by_user_email VARCHAR(255) NOT NULL,
    pre_merge_snapshot_json TEXT NOT NULL,
    fields_resolved_json TEXT NOT NULL,
    transferred_entities_json TEXT NOT NULL,
    audit_note TEXT,
    merged_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_contact_merge_tenant ON contact_merge_history(tenant_id);
CREATE INDEX IF NOT EXISTS idx_contact_merge_primary ON contact_merge_history(primary_contact_id);
CREATE INDEX IF NOT EXISTS idx_contact_merge_secondary ON contact_merge_history(secondary_contact_id);
CREATE INDEX IF NOT EXISTS idx_contact_merge_merged_at ON contact_merge_history(merged_at DESC);
