-- Add missing performance indexes to fix SLOW_OPERATION warnings

-- Index for fast user lookup by email
CREATE INDEX IF NOT EXISTS idx_user_email ON app_users(email);

-- Index for fetching all leads by contact_id
CREATE INDEX IF NOT EXISTS idx_lead_contact ON leads(contact_id);

-- Index for fetching messages by contact_id and sorting by timestamp
CREATE INDEX IF NOT EXISTS idx_chat_msg_contact_time ON chat_messages(contact_id, timestamp);

-- Index for simply fetching messages by contact_id
CREATE INDEX IF NOT EXISTS idx_chat_msg_contact ON chat_messages(contact_id);
