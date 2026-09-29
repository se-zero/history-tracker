-- Spring Authorization Server 7.0.4가 배포하는 기본 스키마
-- (oauth2-registered-client-schema.sql, oauth2-authorization-schema.sql, oauth2-authorization-consent-schema.sql)를
-- PostgreSQL로 옮긴 것. 원본 스크립트 안내대로 blob→TEXT, timestamp→TIMESTAMPTZ로 바꿨고,
-- 추가로 varchar(n)→TEXT로 전부 바꿨다 — CIMD 클라이언트의 client_id는 URL이고 설정 JSON
-- 컬럼(client_settings 등)은 원본 varchar(2000) 상한이 비좁다. JdbcOAuth2AuthorizationService는
-- 기동 시 컬럼 메타데이터를 읽어 TEXT/BLOB 여부에 맞춰 바인딩하므로 TEXT로도 그대로 동작한다.
-- JPA 엔티티가 없어 ddl-auto: validate 대상이 아니다.
CREATE TABLE oauth2_registered_client (
    id TEXT NOT NULL,
    client_id TEXT NOT NULL,
    client_id_issued_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP NOT NULL,
    client_secret TEXT DEFAULT NULL,
    client_secret_expires_at TIMESTAMPTZ DEFAULT NULL,
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
    authorization_code_issued_at TIMESTAMPTZ DEFAULT NULL,
    authorization_code_expires_at TIMESTAMPTZ DEFAULT NULL,
    authorization_code_metadata TEXT DEFAULT NULL,
    access_token_value TEXT DEFAULT NULL,
    access_token_issued_at TIMESTAMPTZ DEFAULT NULL,
    access_token_expires_at TIMESTAMPTZ DEFAULT NULL,
    access_token_metadata TEXT DEFAULT NULL,
    access_token_type TEXT DEFAULT NULL,
    access_token_scopes TEXT DEFAULT NULL,
    oidc_id_token_value TEXT DEFAULT NULL,
    oidc_id_token_issued_at TIMESTAMPTZ DEFAULT NULL,
    oidc_id_token_expires_at TIMESTAMPTZ DEFAULT NULL,
    oidc_id_token_metadata TEXT DEFAULT NULL,
    refresh_token_value TEXT DEFAULT NULL,
    refresh_token_issued_at TIMESTAMPTZ DEFAULT NULL,
    refresh_token_expires_at TIMESTAMPTZ DEFAULT NULL,
    refresh_token_metadata TEXT DEFAULT NULL,
    user_code_value TEXT DEFAULT NULL,
    user_code_issued_at TIMESTAMPTZ DEFAULT NULL,
    user_code_expires_at TIMESTAMPTZ DEFAULT NULL,
    user_code_metadata TEXT DEFAULT NULL,
    device_code_value TEXT DEFAULT NULL,
    device_code_issued_at TIMESTAMPTZ DEFAULT NULL,
    device_code_expires_at TIMESTAMPTZ DEFAULT NULL,
    device_code_metadata TEXT DEFAULT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_oauth2_authorization_principal_name ON oauth2_authorization (principal_name);
CREATE INDEX idx_oauth2_authorization_registered_client_id ON oauth2_authorization (registered_client_id);
-- Spring 기본 스키마에는 토큰 값 컬럼 인덱스가 없어 토큰 교환·refresh마다 findByToken이 풀스캔한다.
-- 우리가 쓰는 네 컬럼(state·인가 코드·access·refresh)만 건다 — OIDC id_token·device/user code는 발급하지 않는다.
CREATE INDEX idx_oauth2_authorization_state ON oauth2_authorization (state);
CREATE INDEX idx_oauth2_authorization_authorization_code_value ON oauth2_authorization (authorization_code_value);
CREATE INDEX idx_oauth2_authorization_access_token_value ON oauth2_authorization (access_token_value);
CREATE INDEX idx_oauth2_authorization_refresh_token_value ON oauth2_authorization (refresh_token_value);

CREATE TABLE oauth2_authorization_consent (
    registered_client_id TEXT NOT NULL,
    principal_name TEXT NOT NULL,
    authorities TEXT NOT NULL,
    PRIMARY KEY (registered_client_id, principal_name)
);
