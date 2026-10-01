-- MCP 클라이언트(코딩 에이전트)의 작업 폴더 → 프로젝트 연결. 키는 (계정, 폴더 절대 경로)다.
-- 계정 파기나 프로젝트 삭제 때 연결이 함께 사라지도록 두 FK 모두 CASCADE로 둔다
-- (남으면 존재하지 않는 프로젝트를 가리키는 행이 된다).
CREATE TABLE mcp_workspace_bindings (
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    workspace_path TEXT        NOT NULL,
    project_id     UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, workspace_path)
);
CREATE INDEX idx_mcp_workspace_bindings_project_id ON mcp_workspace_bindings (project_id);
