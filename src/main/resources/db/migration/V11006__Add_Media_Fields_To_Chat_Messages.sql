-- Flyway Migration V11006: Add Media Fields to Chat Messages for WhatsApp Media Support

ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS media_url VARCHAR(1000);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS media_type VARCHAR(50);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS mime_type VARCHAR(100);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS file_name VARCHAR(255);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS file_size BIGINT;
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS media_id VARCHAR(255);
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS thumbnail_url VARCHAR(1000);

CREATE INDEX IF NOT EXISTS idx_chat_msg_media_id ON chat_messages(media_id);
