CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider TEXT NOT NULL,
    provider_user_id TEXT NOT NULL,
    email CITEXT NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_users_provider_user_id_active
    ON users (provider, provider_user_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_users_email
    ON users (email);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_refresh_tokens_token_hash
    ON refresh_tokens (token_hash);

CREATE INDEX idx_refresh_tokens_user_id
    ON refresh_tokens (user_id);

CREATE TABLE github_installations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id BIGINT NOT NULL,
    account_type TEXT NOT NULL,
    account_login TEXT NOT NULL,
    installer_user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    encrypted_installation_token BYTEA,
    installation_token_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_github_installations_installation_id
    ON github_installations (installation_id);

CREATE INDEX idx_github_installations_installer_user_id
    ON github_installations (installer_user_id);
