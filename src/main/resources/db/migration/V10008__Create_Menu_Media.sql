CREATE TABLE IF NOT EXISTS menu_media (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    image_data BYTEA,
    content_type VARCHAR(255),
    CONSTRAINT fk_menu_media_owner FOREIGN KEY (owner_id) REFERENCES app_users(id)
);

