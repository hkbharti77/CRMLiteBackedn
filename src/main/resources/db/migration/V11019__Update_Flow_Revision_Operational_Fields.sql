-- Add advanced meta operational and deprecation tracking fields to FlowRevision
ALTER TABLE flow_revisions 
ADD COLUMN IF NOT EXISTS revision_status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
ADD COLUMN IF NOT EXISTS validation_errors_json TEXT,
ADD COLUMN IF NOT EXISTS meta_status VARCHAR(50),
ADD COLUMN IF NOT EXISTS meta_health_json TEXT,
ADD COLUMN IF NOT EXISTS meta_can_send_message BOOLEAN,
ADD COLUMN IF NOT EXISTS deprecated_at TIMESTAMP WITHOUT TIME ZONE,
ADD COLUMN IF NOT EXISTS deprecation_status VARCHAR(50),
ADD COLUMN IF NOT EXISTS deprecation_attempts INT DEFAULT 0,
ADD COLUMN IF NOT EXISTS last_deprecation_error VARCHAR(1000);

-- Add missing fields to flow_send_sessions
ALTER TABLE flow_send_sessions
ADD COLUMN IF NOT EXISTS message_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS campaign_id VARCHAR(255),
ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW();
