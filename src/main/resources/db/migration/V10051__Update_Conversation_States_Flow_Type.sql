-- Drop the existing check constraint on flow_type that prevents 'SUPPORT' from being inserted
ALTER TABLE conversation_states DROP CONSTRAINT IF EXISTS conversation_states_flow_type_check;

-- Add the new constraint including 'SUPPORT'
ALTER TABLE conversation_states ADD CONSTRAINT conversation_states_flow_type_check 
CHECK (flow_type::text IN ('APPOINTMENT', 'BOOKING', 'ENQUIRY', 'LEAD_CAPTURE', 'SUPPORT'));
