-- 앱 수준(프로젝트 무관) Atlassian 봇 자격증명 1행 테이블. refresh token이 갱신마다
-- 회전해 런타임 저장이 필요하다.
CREATE TABLE app_credentials (
    provider             TEXT PRIMARY KEY,
    encrypted_credential BYTEA NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL
);
