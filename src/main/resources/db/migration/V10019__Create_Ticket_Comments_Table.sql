-- Create missing ticket_comments table
-- This table was referenced in the code but missing from the original migration

CREATE TABLE ticket_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    author_id UUID REFERENCES app_users(id) ON DELETE SET NULL,
    author_name VARCHAR(255) NOT NULL,
    author_type VARCHAR(50) NOT NULL DEFAULT 'AGENT',
    message TEXT NOT NULL,
    internal BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP,
    deleted_by UUID REFERENCES app_users(id) ON DELETE SET NULL
);

-- Create indexes for better performance
CREATE INDEX idx_ticket_comments_ticket_id ON ticket_comments(ticket_id);
CREATE INDEX idx_ticket_comments_author_id ON ticket_comments(author_id);
CREATE INDEX idx_ticket_comments_created_at ON ticket_comments(created_at);
CREATE INDEX idx_ticket_comments_deleted ON ticket_comments(deleted);