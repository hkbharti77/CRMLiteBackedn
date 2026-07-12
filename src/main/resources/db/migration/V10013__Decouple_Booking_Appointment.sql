-- Step 1: Add contact_id columns
ALTER TABLE bookings ADD COLUMN IF NOT EXISTS contact_id UUID;
ALTER TABLE appointments ADD COLUMN IF NOT EXISTS contact_id UUID;

-- Step 2: Populate contact_id from existing leads
UPDATE bookings b
SET contact_id = l.contact_id
FROM leads l
WHERE b.lead_id = l.id;

UPDATE appointments a
SET contact_id = l.contact_id
FROM leads l
WHERE a.lead_id = l.id;

-- Clean up any orphans (if any exist where lead was deleted)
DELETE FROM bookings WHERE contact_id IS NULL;
DELETE FROM appointments WHERE contact_id IS NULL;

-- Step 3: Make contact_id NOT NULL
ALTER TABLE bookings ALTER COLUMN contact_id SET NOT NULL;
ALTER TABLE appointments ALTER COLUMN contact_id SET NOT NULL;

-- Step 4: Drop lead_id column and constraints
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS fk_bookings_lead;
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_lead_id_fkey;
ALTER TABLE bookings DROP COLUMN IF EXISTS lead_id;

ALTER TABLE appointments DROP CONSTRAINT IF EXISTS fk_appointments_lead;
ALTER TABLE appointments DROP CONSTRAINT IF EXISTS appointments_lead_id_fkey;
ALTER TABLE appointments DROP COLUMN IF EXISTS lead_id;

-- Step 5: Add Foreign Key constraints for contact_id
ALTER TABLE bookings ADD CONSTRAINT fk_bookings_contact FOREIGN KEY (contact_id) REFERENCES contacts(id);
ALTER TABLE appointments ADD CONSTRAINT fk_appointments_contact FOREIGN KEY (contact_id) REFERENCES contacts(id);
