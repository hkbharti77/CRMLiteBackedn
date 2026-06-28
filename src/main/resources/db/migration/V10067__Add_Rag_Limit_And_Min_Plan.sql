-- V10059: Add Rag Limit And Min Plan

-- 1. Add has_rag_llm column to subscription_plans
ALTER TABLE subscription_plans ADD COLUMN has_rag_llm BOOLEAN DEFAULT TRUE NOT NULL;

-- 2. Add MIN Plan (RAG LLM disabled, Menu only)
INSERT INTO subscription_plans (id, name, price_monthly, price_yearly, employee_limit, primary_resource_limit, secondary_resource_limit, ticket_limit, email_limit, has_whatsapp, has_custom_widget, has_rag_llm)
VALUES 
('MIN', 'Starter Menu-Bot', 999.00, 9990.00, 3, 2500, 500, 500, 3000, TRUE, TRUE, FALSE)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    price_monthly = EXCLUDED.price_monthly,
    price_yearly = EXCLUDED.price_yearly,
    employee_limit = EXCLUDED.employee_limit,
    primary_resource_limit = EXCLUDED.primary_resource_limit,
    secondary_resource_limit = EXCLUDED.secondary_resource_limit,
    ticket_limit = EXCLUDED.ticket_limit,
    email_limit = EXCLUDED.email_limit,
    has_whatsapp = EXCLUDED.has_whatsapp,
    has_custom_widget = EXCLUDED.has_custom_widget,
    has_rag_llm = EXCLUDED.has_rag_llm;

-- 3. Update PRO Plan to limit to realistic usage
UPDATE subscription_plans 
SET primary_resource_limit = 25000, 
    secondary_resource_limit = 25000, 
    ticket_limit = 25000, 
    email_limit = 15000 
WHERE id = 'PRO';
