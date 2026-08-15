-- Migration V11005: Add Session Timeout Fields

-- Update web_chat_sessions
ALTER TABLE web_chat_sessions
    ADD COLUMN status VARCHAR(50) DEFAULT 'ACTIVE' NOT NULL,
    ADD COLUMN last_activity_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ADD COLUMN timeout_started_at TIMESTAMP,
    ADD COLUMN closed_at TIMESTAMP,
    ADD COLUMN close_reason VARCHAR(255);

-- Update conversation_states (WhatsApp flows)
ALTER TABLE conversation_states
    ADD COLUMN session_status VARCHAR(50) DEFAULT 'ACTIVE' NOT NULL,
    ADD COLUMN last_activity_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ADD COLUMN timeout_started_at TIMESTAMP,
    ADD COLUMN closed_at TIMESTAMP,
    ADD COLUMN close_reason VARCHAR(255),
    ADD COLUMN previous_state VARCHAR(255);
