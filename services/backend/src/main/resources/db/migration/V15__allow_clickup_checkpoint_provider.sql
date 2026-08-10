-- checkpoints.provider CHECK은 새 provider를 붙일 때마다 이 테이블만은 여전히 마이그레이션이 필요하다.
ALTER TABLE checkpoints DROP CONSTRAINT chk_checkpoints_provider;

ALTER TABLE checkpoints ADD CONSTRAINT chk_checkpoints_provider
    CHECK (provider IN ('github', 'jira', 'slack', 'linear', 'asana', 'clickup'));
