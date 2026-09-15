-- Decouple bookings/appointments from leads → contact_id.
-- Idempotent: safe when lead_id was never present or already dropped.

ALTER TABLE bookings ADD COLUMN IF NOT EXISTS contact_id UUID;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS contact_id UUID;

DO $$
BEGIN
    -- Backfill from leads only if legacy lead_id still exists
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'bookings' AND column_name = 'lead_id'
    ) THEN
        UPDATE bookings b
        SET contact_id = l.contact_id
        FROM leads l
        WHERE b.lead_id = l.id
          AND b.contact_id IS NULL;

        DELETE FROM bookings WHERE contact_id IS NULL;

        ALTER TABLE bookings DROP CONSTRAINT IF EXISTS fk_bookings_lead;
        ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_lead_id_fkey;
        ALTER TABLE bookings DROP COLUMN IF EXISTS lead_id;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'appointments' AND column_name = 'lead_id'
    ) THEN
        UPDATE appointments a
        SET contact_id = l.contact_id
        FROM leads l
        WHERE a.lead_id = l.id
          AND a.contact_id IS NULL;

        DELETE FROM appointments WHERE contact_id IS NULL;

        ALTER TABLE appointments DROP CONSTRAINT IF EXISTS fk_appointments_lead;
        ALTER TABLE appointments DROP CONSTRAINT IF EXISTS appointments_lead_id_fkey;
        ALTER TABLE appointments DROP COLUMN IF EXISTS lead_id;
    END IF;

    -- Enforce NOT NULL only when every row is populated (or table empty)
    IF NOT EXISTS (SELECT 1 FROM bookings WHERE contact_id IS NULL) THEN
        ALTER TABLE bookings ALTER COLUMN contact_id SET NOT NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM appointments WHERE contact_id IS NULL) THEN
        ALTER TABLE appointments ALTER COLUMN contact_id SET NOT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_bookings_contact'
    ) THEN
        ALTER TABLE bookings
            ADD CONSTRAINT fk_bookings_contact
            FOREIGN KEY (contact_id) REFERENCES contacts(id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_appointments_contact'
    ) THEN
        ALTER TABLE appointments
            ADD CONSTRAINT fk_appointments_contact
            FOREIGN KEY (contact_id) REFERENCES contacts(id);
    END IF;
END $$;
