-- Add AI Persona columns to tenants table
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS ai_persona_prompt TEXT;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS ai_persona_updated_at TIMESTAMP;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS ai_persona_updated_by UUID;
