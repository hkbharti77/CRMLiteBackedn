ALTER TABLE tenants
ADD COLUMN IF NOT EXISTS force_show_appointment BOOLEAN;
