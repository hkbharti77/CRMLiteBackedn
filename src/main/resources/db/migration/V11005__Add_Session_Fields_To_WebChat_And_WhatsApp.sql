-- Migration V11005: Add Session Timeout Fields

-- Update web_chat_sessions
ALTER TABLE web_chat_sessions
    ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ACTIVE' NOT NULL,
    ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ADD COLUMN IF NOT EXISTS timeout_started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS close_reason VARCHAR(255);

-- Update conversation_states (WhatsApp flows)
ALTER TABLE conversation_states
    ADD COLUMN IF NOT EXISTS session_status VARCHAR(50) DEFAULT 'ACTIVE' NOT NULL,
    ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ADD COLUMN IF NOT EXISTS timeout_started_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS closed_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS close_reason VARCHAR(255),
    ADD COLUMN IF NOT EXISTS previous_state VARCHAR(255);
