-- ============================================================
-- V10030: Normalize tags (AP-2 fix)
-- Replaces @ElementCollection tags on contacts, leads, chat_messages
-- with a proper tags lookup table + join tables.
-- Backfills existing tag data from Hibernate-generated shadow tables.
-- ============================================================

-- 1. Rename existing shadow tables if they exist (prevents 'CREATE TABLE IF NOT EXISTS' skipping)
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'contact_tags' AND schemaname = 'public') THEN
        ALTER TABLE contact_tags RENAME TO contact_tags_old;
    END IF;
    IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'lead_tags' AND schemaname = 'public') THEN
        ALTER TABLE lead_tags RENAME TO lead_tags_old;
    END IF;
    IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'chat_messages_tags' AND schemaname = 'public') THEN
        ALTER TABLE chat_messages_tags RENAME TO message_tags_old;
    END IF;
END $$;

-- 2. Create the tags lookup table
CREATE TABLE IF NOT EXISTS tags (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    owner_id    UUID         NOT NULL,
    entity_type VARCHAR(30)  NOT NULL,  -- CONTACT | LEAD | MESSAGE
    name        VARCHAR(100) NOT NULL,
    color       VARCHAR(20),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_tags PRIMARY KEY (id),
    CONSTRAINT fk_tag_owner FOREIGN KEY (owner_id)
        REFERENCES app_users(id) ON DELETE CASCADE,
    CONSTRAINT uk_tag_owner_type_name UNIQUE (owner_id, entity_type, name),
    CONSTRAINT chk_tag_entity_type CHECK (entity_type IN ('CONTACT','LEAD','MESSAGE'))
);

CREATE INDEX IF NOT EXISTS idx_tag_owner_type ON tags (owner_id, entity_type);

-- 3. Create join tables (proper M:N with FKs)
-- We drop them first in case a previous failed run left them in a broken state
DROP TABLE IF EXISTS contact_tags;
DROP TABLE IF EXISTS lead_tags;
DROP TABLE IF EXISTS message_tags;

CREATE TABLE IF NOT EXISTS contact_tags (
    contact_id UUID NOT NULL,
    tag_id     UUID NOT NULL,
    CONSTRAINT pk_contact_tags PRIMARY KEY (contact_id, tag_id),
    CONSTRAINT fk_ct_contact FOREIGN KEY (contact_id)
        REFERENCES contacts(id) ON DELETE CASCADE,
    CONSTRAINT fk_ct_tag FOREIGN KEY (tag_id)
        REFERENCES tags(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ct_tag_id ON contact_tags (tag_id);

CREATE TABLE IF NOT EXISTS lead_tags (
    lead_id UUID NOT NULL,
    tag_id  UUID NOT NULL,
    CONSTRAINT pk_lead_tags PRIMARY KEY (lead_id, tag_id),
    CONSTRAINT fk_lt_lead FOREIGN KEY (lead_id)
        REFERENCES leads(id) ON DELETE CASCADE,
    CONSTRAINT fk_lt_tag FOREIGN KEY (tag_id)
        REFERENCES tags(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_lt_tag_id ON lead_tags (tag_id);

CREATE TABLE IF NOT EXISTS message_tags (
    message_id UUID NOT NULL,
    tag_id     UUID NOT NULL,
    CONSTRAINT pk_message_tags PRIMARY KEY (message_id, tag_id),
    CONSTRAINT fk_mt_message FOREIGN KEY (message_id)
        REFERENCES chat_messages(id) ON DELETE CASCADE,
    CONSTRAINT fk_mt_tag FOREIGN KEY (tag_id)
        REFERENCES tags(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_mt_tag_id ON message_tags (tag_id);

-- 4. Backfill contact tags from the renamed shadow table
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'contact_tags_old') THEN
        -- Insert into canonical tags table first (avoiding duplicates)
        INSERT INTO tags (owner_id, entity_type, name)
        SELECT DISTINCT c.owner_id, 'CONTACT', ct.tags
        FROM contact_tags_old ct
        JOIN contacts c ON c.id = ct.contact_id
        WHERE ct.tags IS NOT NULL AND ct.tags <> ''
        ON CONFLICT (owner_id, entity_type, name) DO NOTHING;

        -- Then insert join rows
        INSERT INTO contact_tags (contact_id, tag_id)
        SELECT ct.contact_id, t.id
        FROM contact_tags_old ct
        JOIN contacts c ON c.id = ct.contact_id
        JOIN tags t ON t.owner_id = c.owner_id
                   AND t.entity_type = 'CONTACT'
                   AND t.name = ct.tags
        ON CONFLICT DO NOTHING;
    END IF;
END $$;

-- 5. Backfill lead tags
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'lead_tags_old') THEN
        INSERT INTO tags (owner_id, entity_type, name)
        SELECT DISTINCT l.owner_id, 'LEAD', lt.tags
        FROM lead_tags_old lt
        JOIN leads l ON l.id = lt.lead_id
        WHERE lt.tags IS NOT NULL AND lt.tags <> ''
        ON CONFLICT (owner_id, entity_type, name) DO NOTHING;

        INSERT INTO lead_tags (lead_id, tag_id)
        SELECT lt.lead_id, t.id
        FROM lead_tags_old lt
        JOIN leads l ON l.id = lt.lead_id
        JOIN tags t ON t.owner_id = l.owner_id
                   AND t.entity_type = 'LEAD'
                   AND t.name = lt.tags
        ON CONFLICT DO NOTHING;
    END IF;
END $$;

-- 6. Backfill message tags
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_tables WHERE tablename = 'message_tags_old') THEN
        INSERT INTO tags (owner_id, entity_type, name)
        SELECT DISTINCT m.owner_id, 'MESSAGE', mt.tags
        FROM message_tags_old mt
        JOIN chat_messages m ON m.id = mt.message_id
        WHERE mt.tags IS NOT NULL AND mt.tags <> ''
        ON CONFLICT (owner_id, entity_type, name) DO NOTHING;

        INSERT INTO message_tags (message_id, tag_id)
        SELECT mt.message_id, t.id
        FROM message_tags_old mt
        JOIN chat_messages m ON m.id = mt.message_id
        JOIN tags t ON t.owner_id = m.owner_id
                   AND t.entity_type = 'MESSAGE'
                   AND t.name = mt.tags
        ON CONFLICT DO NOTHING;
    END IF;
END $$;
