DO $$ 
BEGIN 
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='subscription_plans' AND column_name='lead_limit'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='subscription_plans' AND column_name='primary_resource_limit'
    ) THEN
        ALTER TABLE subscription_plans RENAME COLUMN lead_limit TO primary_resource_limit;
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='subscription_plans' AND column_name='lead_limit'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='subscription_plans' AND column_name='primary_resource_limit'
    ) THEN
        UPDATE subscription_plans SET primary_resource_limit = lead_limit WHERE (primary_resource_limit IS NULL OR primary_resource_limit = 0) AND lead_limit > 0;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='subscription_plans' AND column_name='booking_limit'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='subscription_plans' AND column_name='secondary_resource_limit'
    ) THEN
        ALTER TABLE subscription_plans RENAME COLUMN booking_limit TO secondary_resource_limit;
    ELSIF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='subscription_plans' AND column_name='booking_limit'
    ) AND EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name='subscription_plans' AND column_name='secondary_resource_limit'
    ) THEN
        UPDATE subscription_plans SET secondary_resource_limit = booking_limit WHERE (secondary_resource_limit IS NULL OR secondary_resource_limit = 0) AND booking_limit > 0;
    END IF;
END $$;

-- Add primary_resource to tenants with a default
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS primary_resource VARCHAR(50) DEFAULT 'LEAD' NOT NULL;

