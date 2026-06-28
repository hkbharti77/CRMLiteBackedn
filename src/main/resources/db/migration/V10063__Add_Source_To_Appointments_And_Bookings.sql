-- Add source column to appointments
ALTER TABLE appointments ADD COLUMN source VARCHAR(50) DEFAULT 'MANUAL';

-- Add source column to bookings
ALTER TABLE bookings ADD COLUMN source VARCHAR(50) DEFAULT 'MANUAL';
