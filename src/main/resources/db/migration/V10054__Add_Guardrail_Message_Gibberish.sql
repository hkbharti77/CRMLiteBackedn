ALTER TABLE whatsapp_configs RENAME COLUMN guardrail_message TO guardrail_message_abuse;
ALTER TABLE whatsapp_configs ADD COLUMN guardrail_message_gibberish TEXT;
