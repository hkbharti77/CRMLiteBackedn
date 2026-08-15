-- Drop the exact constraint mentioned in the error
ALTER TABLE whatsapp_campaign_recipients DROP CONSTRAINT IF EXISTS fk1g3a3a0uyt37u9rvsr6oyd4qq;

-- Dynamically drop any other foreign keys on contact_id just in case the name was different in other environments
DO $$ DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
        WHERE tc.constraint_type = 'FOREIGN KEY'
          AND tc.table_name = 'whatsapp_campaign_recipients'
          AND kcu.column_name = 'contact_id'
    ) LOOP
        EXECUTE 'ALTER TABLE whatsapp_campaign_recipients DROP CONSTRAINT IF EXISTS ' || quote_ident(r.constraint_name);
    END LOOP;
END $$;

-- Add the foreign key back with ON DELETE SET NULL and a predictable name
ALTER TABLE whatsapp_campaign_recipients 
ADD CONSTRAINT fk_wa_camp_recip_contact 
FOREIGN KEY (contact_id) REFERENCES contacts(id) ON DELETE SET NULL;
