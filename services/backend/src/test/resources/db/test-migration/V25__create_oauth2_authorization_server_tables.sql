-- H2(테스트 DB)는 TIMESTAMPTZ가 없다 — TIMESTAMP WITH TIME ZONE으로 바꾼다. 나머지는 운영(Postgres)
-- 마이그레이션과 동일하다.
CREATE TABLE oauth2_registered_client (
    id TEXT NOT NULL,
    client_id TEXT NOT NULL,
    client_id_issued_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret TEXT DEFAULT NULL,
    client_secret_expires_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    client_name TEXT NOT NULL,
    client_authentication_methods TEXT NOT NULL,
    authorization_grant_types TEXT NOT NULL,
    redirect_uris TEXT DEFAULT NULL,
    post_logout_redirect_uris TEXT DEFAULT NULL,
    scopes TEXT NOT NULL,
    client_settings TEXT NOT NULL,
    token_settings TEXT NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_oauth2_registered_client_client_id ON oauth2_registered_client (client_id);

CREATE TABLE oauth2_authorization (
    id TEXT NOT NULL,
    registered_client_id TEXT NOT NULL,
    principal_name TEXT NOT NULL,
    authorization_grant_type TEXT NOT NULL,
    authorized_scopes TEXT DEFAULT NULL,
    attributes TEXT DEFAULT NULL,
    state TEXT DEFAULT NULL,
    authorization_code_value TEXT DEFAULT NULL,
    authorization_code_issued_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    authorization_code_expires_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    authorization_code_metadata TEXT DEFAULT NULL,
    access_token_value TEXT DEFAULT NULL,
    access_token_issued_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    access_token_expires_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    access_token_metadata TEXT DEFAULT NULL,
    access_token_type TEXT DEFAULT NULL,
    access_token_scopes TEXT DEFAULT NULL,
    oidc_id_token_value TEXT DEFAULT NULL,
    oidc_id_token_issued_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    oidc_id_token_expires_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    oidc_id_token_metadata TEXT DEFAULT NULL,
    refresh_token_value TEXT DEFAULT NULL,
    refresh_token_issued_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    refresh_token_expires_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    refresh_token_metadata TEXT DEFAULT NULL,
    user_code_value TEXT DEFAULT NULL,
    user_code_issued_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    user_code_expires_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    user_code_metadata TEXT DEFAULT NULL,
    device_code_value TEXT DEFAULT NULL,
    device_code_issued_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    device_code_expires_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
    device_code_metadata TEXT DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_oauth2_authorization_principal_name ON oauth2_authorization (principal_name);
CREATE INDEX idx_oauth2_authorization_registered_client_id ON oauth2_authorization (registered_client_id);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id TEXT NOT NULL,
    principal_name TEXT NOT NULL,
    authorities TEXT NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
