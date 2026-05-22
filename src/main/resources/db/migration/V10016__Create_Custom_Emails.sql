-- V10016: Custom Email Campaigns
-- Tenants compose and send custom emails to their own contacts/clients.

CREATE TABLE custom_emails (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      UUID         NOT NULL REFERENCES app_users(id),

    -- Compose fields
    subject       VARCHAR(255) NOT NULL,
    body          TEXT         NOT NULL,          -- plain-text or simple HTML body written by tenant
    cta_label     VARCHAR(100),                   -- optional button label
    cta_url       VARCHAR(500),                   -- optional button URL

    -- Targeting
    recipient_mode VARCHAR(20) NOT NULL DEFAULT 'ALL',  -- ALL | TAGGED | MANUAL
    tags_filter    TEXT,                               -- comma-separated tags when mode=TAGGED

    -- Status
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',  -- DRAFT | SENT | FAILED
    sent_at       TIMESTAMP,
    total_sent    INT          NOT NULL DEFAULT 0,
    total_failed  INT          NOT NULL DEFAULT 0,

    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP
);

CREATE INDEX idx_custom_emails_owner ON custom_emails(owner_id);
CREATE INDEX idx_custom_emails_status ON custom_emails(status);
