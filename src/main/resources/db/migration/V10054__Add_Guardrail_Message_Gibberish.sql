DO $$ 
BEGIN 
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='whatsapp_configs' AND column_name='guardrail_message'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='whatsapp_configs' AND column_name='guardrail_message_abuse'
    ) THEN
        ALTER TABLE whatsapp_configs RENAME COLUMN guardrail_message TO guardrail_message_abuse;
    END IF;
END $$;

ALTER TABLE whatsapp_configs ADD COLUMN IF NOT EXISTS guardrail_message_gibberish TEXT;
