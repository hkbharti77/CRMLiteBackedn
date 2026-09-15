ALTER TABLE tenants ADD COLUMN IF NOT EXISTS email_header_text TEXT;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS email_footer_text TEXT;
