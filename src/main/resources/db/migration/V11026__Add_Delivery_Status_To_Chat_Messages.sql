-- Add delivery_status column to chat_messages with NULL default for truthful historical representation
ALTER TABLE chat_messages
    ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(20) NULL;

CREATE INDEX IF NOT EXISTS idx_chat_msg_delivery_status
    ON chat_messages(delivery_status)
    WHERE delivery_status IS NOT NULL;
