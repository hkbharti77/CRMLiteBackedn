-- Add missing user_name column to ticket_activities table
-- This column is used to store the display name of the user who performed the activity

ALTER TABLE ticket_activities 
ADD COLUMN user_name VARCHAR(255);

-- Update existing records to populate user_name from the user table
UPDATE ticket_activities 
SET user_name = COALESCE(u.display_name, u.email, 'System')
FROM app_users u 
WHERE ticket_activities.user_id = u.id 
AND ticket_activities.user_name IS NULL;

-- Set default value for records without a user (system activities)
UPDATE ticket_activities 
SET user_name = 'System' 
WHERE user_name IS NULL;