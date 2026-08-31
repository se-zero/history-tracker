CREATE TABLE github_user_credentials (
    user_id UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    encrypted_credential BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
