-- Database Migration: V10024__Add_Flow_State_Machine.sql
-- Description: Creates the flow_definitions table and updates conversation_states to support the state machine engine.

-- 1. Create flow_definitions table
CREATE TABLE flow_definitions (
    id UUID PRIMARY KEY,
    tenant_id UUID REFERENCES app_users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    flow_type VARCHAR(50) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    definition_json JSONB NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tenant_flow_version UNIQUE (tenant_id, flow_type, version)
);

-- 2. Modify conversation_states table
-- Drop the old current_step logic
ALTER TABLE conversation_states DROP COLUMN IF EXISTS current_step;

-- Add new state machine columns
ALTER TABLE conversation_states ADD COLUMN current_state VARCHAR(255) NOT NULL DEFAULT 'START';
ALTER TABLE conversation_states ADD COLUMN flow_definition_id UUID REFERENCES flow_definitions(id) ON DELETE SET NULL;
ALTER TABLE conversation_states ADD COLUMN state_history JSONB NOT NULL DEFAULT '[]'::jsonb;
