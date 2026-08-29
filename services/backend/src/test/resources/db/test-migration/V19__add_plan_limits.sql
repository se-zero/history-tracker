ALTER TABLE users ADD COLUMN plan TEXT NOT NULL DEFAULT 'FREE';
ALTER TABLE users ADD COLUMN free_query_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE user_provider_connections (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    first_connected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, provider)
);

ALTER TABLE integrations ADD COLUMN incremental_enabled BOOLEAN NOT NULL DEFAULT TRUE;
