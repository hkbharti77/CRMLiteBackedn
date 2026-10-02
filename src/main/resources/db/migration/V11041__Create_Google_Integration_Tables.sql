-- Migration: Google Integration Tables
-- Creates all tables needed for the Google OAuth integration layer.
-- Replaces old google_access_token/google_refresh_token/google_token_expiry fields
-- that were stored directly on app_users (those fields will be removed in a later migration
-- after GoogleConnection is fully live and tested).

-- ── Step 1: Add googleSubjectId to app_users ──────────────────────────────
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS google_subject_id VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_google_subject_id ON app_users(google_subject_id);

-- ── Step 2: google_connections ────────────────────────────────────────────
-- Stores OAuth2 credential sets (encrypted access + refresh tokens) per user.
-- One row per active OAuth session. revokedAt IS NULL = active.
CREATE TABLE IF NOT EXISTS google_connections (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL,
    google_subject_id       VARCHAR(255),
    access_token_encrypted  TEXT,
    refresh_token_encrypted TEXT,
    expires_at              TIMESTAMP,
    granted_scopes          TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP NOT NULL DEFAULT now(),
    revoked_at              TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_gconn_user_id ON google_connections(user_id);
CREATE INDEX IF NOT EXISTS idx_gconn_google_subject_id ON google_connections(google_subject_id);

-- ── Step 3: google_integrations ───────────────────────────────────────────
-- Tracks per-feature status (CONNECTED, PARTIAL, REAUTH_REQUIRED, etc.)
-- for each GoogleConnection. One row per feature per connection.
CREATE TABLE IF NOT EXISTS google_integrations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id    UUID NOT NULL REFERENCES google_connections(id) ON DELETE CASCADE,
    feature          VARCHAR(30) NOT NULL,
    status           VARCHAR(25) NOT NULL,
    connected_at     TIMESTAMP,
    disconnected_at  TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_gint_connection_id ON google_integrations(connection_id);
CREATE INDEX IF NOT EXISTS idx_gint_feature ON google_integrations(feature);

-- ── Step 4: google_syncs ──────────────────────────────────────────────────
-- CRM ↔ Google resource ID mapping for idempotency.
-- Unique constraints prevent duplicate Google resources on retries.
CREATE TABLE IF NOT EXISTS google_syncs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id       UUID NOT NULL REFERENCES google_connections(id) ON DELETE CASCADE,
    resource_type       VARCHAR(30) NOT NULL,
    crm_resource_id     UUID NOT NULL,
    google_resource_id  VARCHAR(500),
    sync_direction      VARCHAR(20),
    last_synced_at      TIMESTAMP,
    sync_status         VARCHAR(20),
    sync_error          TEXT,
    CONSTRAINT uq_gsync_crm_resource     UNIQUE (connection_id, resource_type, crm_resource_id),
    CONSTRAINT uq_gsync_google_resource  UNIQUE (connection_id, resource_type, google_resource_id)
);
CREATE INDEX IF NOT EXISTS idx_gsync_connection_id ON google_syncs(connection_id);
CREATE INDEX IF NOT EXISTS idx_gsync_crm_resource_id ON google_syncs(crm_resource_id);

-- ── Step 5: user_devices ──────────────────────────────────────────────────
-- Per-device FCM push notification tokens.
-- installationId = Firebase Installation ID (stable per browser/app install).
-- Supports multi-device push (laptop Chrome, Android, etc.)
CREATE TABLE IF NOT EXISTS user_devices (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL,
    installation_id  VARCHAR(200) NOT NULL,
    fcm_token        TEXT NOT NULL,
    platform         VARCHAR(20),
    browser          TEXT,
    device_name      VARCHAR(200),
    last_seen_at     TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    revoked_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_udev_user_id ON user_devices(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_udev_installation_id ON user_devices(installation_id);
