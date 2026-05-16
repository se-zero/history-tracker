CREATE TABLE users (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    provider TEXT NOT NULL,
    provider_user_id TEXT NOT NULL,
    email TEXT NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX uq_users_provider_user_id_active
    ON users (provider, provider_user_id);

CREATE INDEX idx_users_email
    ON users (email);

CREATE TABLE refresh_tokens (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_refresh_tokens_token_hash
    ON refresh_tokens (token_hash);

CREATE INDEX idx_refresh_tokens_user_id
    ON refresh_tokens (user_id);

CREATE TABLE github_installations (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    installation_id BIGINT NOT NULL,
    account_type TEXT NOT NULL,
    account_login TEXT NOT NULL,
    installer_user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    encrypted_installation_token BYTEA,
    installation_token_expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_github_installations_installation_id
    ON github_installations (installation_id);

CREATE INDEX idx_github_installations_installer_user_id
    ON github_installations (installer_user_id);
