-- =====================================================================
-- Migration V11054: Create WhatsApp Campaign Tracked Links Table
-- =====================================================================
-- Enables click tracking, conversion attribution, and link redirect analytics
-- for Call-To-Action (CTA) URL buttons and message links in WhatsApp campaigns.

CREATE TABLE IF NOT EXISTS whatsapp_campaign_tracked_links (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID,
    campaign_id         UUID NOT NULL,
    recipient_id        UUID NOT NULL,
    token               VARCHAR(32) NOT NULL UNIQUE,
    original_url        TEXT NOT NULL,
    button_index        INT DEFAULT 0,
    button_text         VARCHAR(100),
    click_count         INT NOT NULL DEFAULT 0,
    first_clicked_at    TIMESTAMP WITHOUT TIME ZONE,
    last_clicked_at     TIMESTAMP WITHOUT TIME ZONE,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW()
);

-- Performance indexes for token lookup and campaign analytics rollups
CREATE INDEX IF NOT EXISTS idx_wa_tracked_links_token  ON whatsapp_campaign_tracked_links (token);
CREATE INDEX IF NOT EXISTS idx_wa_tracked_links_camp   ON whatsapp_campaign_tracked_links (campaign_id);
CREATE INDEX IF NOT EXISTS idx_wa_tracked_links_recip  ON whatsapp_campaign_tracked_links (recipient_id);
CREATE INDEX IF NOT EXISTS idx_wa_tracked_links_tenant ON whatsapp_campaign_tracked_links (tenant_id);

-- Recipient-level click tracking timestamps & atomic counters
ALTER TABLE whatsapp_campaign_recipients
ADD COLUMN IF NOT EXISTS first_clicked_at TIMESTAMP WITHOUT TIME ZONE,
ADD COLUMN IF NOT EXISTS last_clicked_at  TIMESTAMP WITHOUT TIME ZONE,
ADD COLUMN IF NOT EXISTS click_count       INT NOT NULL DEFAULT 0;
