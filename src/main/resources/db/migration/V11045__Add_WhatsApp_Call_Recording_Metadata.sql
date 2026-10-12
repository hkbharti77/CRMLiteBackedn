-- Flyway Migration: V11045__Add_WhatsApp_Call_Recording_Metadata.sql
-- Enforces persistence of Cloudinary call recordings, metadata, format, and playback status

ALTER TABLE whatsapp_call_sessions
ADD COLUMN IF NOT EXISTS recording_url TEXT,
ADD COLUMN IF NOT EXISTS recording_public_id VARCHAR(256),
ADD COLUMN IF NOT EXISTS recording_format VARCHAR(16) DEFAULT 'mp3',
ADD COLUMN IF NOT EXISTS recording_duration_seconds INT,
ADD COLUMN IF NOT EXISTS recording_status VARCHAR(32) DEFAULT 'NOT_AVAILABLE',
ADD COLUMN IF NOT EXISTS recording_size_bytes BIGINT;

CREATE INDEX IF NOT EXISTS idx_wa_call_sessions_recording ON whatsapp_call_sessions(tenant_id, recording_status);

ALTER TABLE voice_sessions
ADD COLUMN IF NOT EXISTS recording_url TEXT;
