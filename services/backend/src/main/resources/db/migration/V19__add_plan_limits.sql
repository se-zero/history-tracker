ALTER TABLE users ADD COLUMN plan TEXT NOT NULL DEFAULT 'FREE';
ALTER TABLE users ADD COLUMN free_query_count INTEGER NOT NULL DEFAULT 0;

CREATE TABLE user_provider_connections (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    first_connected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, provider)
);

ALTER TABLE integrations ADD COLUMN incremental_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 이미 연동한 provider를 이력에 채운다 (해제·삭제해도 남아야 "재연동 불가"가 성립하므로 지금 시점의 사실을 채운다)
INSERT INTO user_provider_connections (user_id, provider)
SELECT DISTINCT p.owner_id, i.provider
FROM integrations i JOIN projects p ON p.id = i.project_id;
