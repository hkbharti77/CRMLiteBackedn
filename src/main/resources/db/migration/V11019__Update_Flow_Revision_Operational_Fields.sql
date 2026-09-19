-- Add advanced meta operational and deprecation tracking fields to FlowRevision
ALTER TABLE flow_revisions 
ADD COLUMN revision_status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
ADD COLUMN validation_errors_json TEXT,
ADD COLUMN meta_status VARCHAR(50),
ADD COLUMN meta_health_json TEXT,
ADD COLUMN meta_can_send_message BOOLEAN,
ADD COLUMN deprecated_at TIMESTAMP WITHOUT TIME ZONE,
ADD COLUMN deprecation_status VARCHAR(50),
ADD COLUMN deprecation_attempts INT DEFAULT 0,
ADD COLUMN last_deprecation_error VARCHAR(1000);

-- Add missing fields to flow_send_sessions
ALTER TABLE flow_send_sessions
ADD COLUMN message_id VARCHAR(255),
ADD COLUMN campaign_id VARCHAR(255),
ADD COLUMN updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW();
