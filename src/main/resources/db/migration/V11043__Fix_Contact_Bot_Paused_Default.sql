-- V11043: Ensure bot_paused has default false and no nulls in contacts
-- Prevents null constraint violations during contact creation/import

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'contacts' AND column_name = 'bot_paused'
    ) THEN
        ALTER TABLE contacts ADD COLUMN bot_paused BOOLEAN NOT NULL DEFAULT false;
    ELSE
        -- Backfill existing nulls if any
        UPDATE contacts SET bot_paused = false WHERE bot_paused IS NULL;
        
        -- Ensure default value is set to false
        ALTER TABLE contacts ALTER COLUMN bot_paused SET DEFAULT false;
        
        -- Ensure not null constraint
        ALTER TABLE contacts ALTER COLUMN bot_paused SET NOT NULL;
    END IF;
END $$;
