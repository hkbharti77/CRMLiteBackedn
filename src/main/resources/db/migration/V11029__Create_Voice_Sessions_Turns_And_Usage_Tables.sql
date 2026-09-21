-- Migration V11029: Create Voice Sessions, Turns, and Usage Tables
-- Ensures tables exist even if Hibernate ddl-auto is set to 'none'

CREATE TABLE IF NOT EXISTS voice_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    tenant_id UUID REFERENCES tenants(id) ON DELETE SET NULL,
    visitor_id VARCHAR(100) NOT NULL,
    language VARCHAR(10) DEFAULT 'en',
    voice_id VARCHAR(50) DEFAULT 'deepgram/flux-tts:free',
    status VARCHAR(20) DEFAULT 'ACTIVE',
    total_turns INT DEFAULT 0,
    active_turn_number INT DEFAULT 0,
    started_at TIMESTAMP WITHOUT TIME ZONE DEFAULT NOW(),
    ended_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

-- Idempotent column additions for existing tables
ALTER TABLE voice_sessions ADD COLUMN IF NOT EXISTS active_turn_number INT DEFAULT 0;
ALTER TABLE voice_sessions ADD COLUMN IF NOT EXISTS total_turns INT DEFAULT 0;
ALTER TABLE voice_sessions ADD COLUMN IF NOT EXISTS voice_id VARCHAR(50) DEFAULT 'deepgram/flux-tts:free';
ALTER TABLE voice_sessions ADD COLUMN IF NOT EXISTS language VARCHAR(10) DEFAULT 'en';
ALTER TABLE voice_sessions ADD COLUMN IF NOT EXISTS status VARCHAR(20) DEFAULT 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_voice_sessions_business ON voice_sessions(business_id);
CREATE INDEX IF NOT EXISTS idx_voice_sessions_tenant ON voice_sessions(tenant_id);

CREATE TABLE IF NOT EXISTS voice_turns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES voice_sessions(id) ON DELETE CASCADE,
    turn_number INT NOT NULL,
    user_transcript TEXT,
    bot_response_text TEXT,
    audio_duration_seconds DOUBLE PRECISION,
    stt_latency_ms INT,
    llm_latency_ms INT,
    tts_latency_ms INT,
    ttfa_ms INT,
    was_interrupted BOOLEAN DEFAULT FALSE,
    detected_language VARCHAR(20) DEFAULT 'en',
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

-- Idempotent column additions for existing voice_turns
ALTER TABLE voice_turns ADD COLUMN IF NOT EXISTS was_interrupted BOOLEAN DEFAULT FALSE;
ALTER TABLE voice_turns ADD COLUMN IF NOT EXISTS detected_language VARCHAR(20) DEFAULT 'en';

CREATE INDEX IF NOT EXISTS idx_voice_turns_session_turn ON voice_turns(session_id, turn_number DESC);

CREATE TABLE IF NOT EXISTS voice_usages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    usage_date DATE NOT NULL,
    stt_seconds_total INT DEFAULT 0,
    tts_characters_total INT DEFAULT 0,
    request_count INT DEFAULT 0,
    estimated_cost_usd NUMERIC(10, 4) DEFAULT 0,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_voice_usages_business_date UNIQUE (business_id, usage_date)
);

-- Idempotent column additions for existing voice_usages
ALTER TABLE voice_usages ADD COLUMN IF NOT EXISTS stt_seconds_total INT DEFAULT 0;
ALTER TABLE voice_usages ADD COLUMN IF NOT EXISTS tts_characters_total INT DEFAULT 0;
ALTER TABLE voice_usages ADD COLUMN IF NOT EXISTS request_count INT DEFAULT 0;
ALTER TABLE voice_usages ADD COLUMN IF NOT EXISTS estimated_cost_usd NUMERIC(10, 4) DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_voice_usages_business_date ON voice_usages(business_id, usage_date);
