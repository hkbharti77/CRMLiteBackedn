-- Migration V11047: Add performance composite indexes for Voice Call Dashboard
CREATE INDEX IF NOT EXISTS idx_voice_sessions_tenant_created ON voice_sessions(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_voice_turns_session_turn ON voice_turns(session_id, turn_number ASC);
CREATE INDEX IF NOT EXISTS idx_wa_call_sessions_tenant_started ON whatsapp_call_sessions(tenant_id, started_at DESC);
