-- V11030: Add WhatsApp Coexistence, Bot Pause Cooldown, and Flow Meta Tracking Fields

-- 1. Contact bot cooldown & pause audit tracking
ALTER TABLE contacts
ADD COLUMN IF NOT EXISTS bot_paused_until TIMESTAMPTZ,
ADD COLUMN IF NOT EXISTS bot_pause_reason VARCHAR(50),
ADD COLUMN IF NOT EXISTS last_agent_reply_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_contacts_bot_paused_until ON contacts(bot_paused_until) WHERE bot_paused_until IS NOT NULL;

-- 2. WhatsApp Config tenant-controlled bot cooldown timer
ALTER TABLE whatsapp_configs
ADD COLUMN IF NOT EXISTS bot_cooldown_minutes INTEGER NOT NULL DEFAULT 15;

-- 3. WhatsApp Flow meta status and lifecycle audit tracking
ALTER TABLE whatsapp_flows
ADD COLUMN IF NOT EXISTS meta_status VARCHAR(50),
ADD COLUMN IF NOT EXISTS last_meta_event_reason VARCHAR(1000);

CREATE INDEX IF NOT EXISTS idx_whatsapp_flows_meta_status ON whatsapp_flows(meta_status);
