-- 프로젝트 수동 정렬 순서. 소유자 단위로 오름차순 정렬한다.
ALTER TABLE projects
    ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

-- 기존 행: 현재 노출 순서(생성 최신순)를 보존하도록 소유자별로 0부터 채번한다.
WITH ordered AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY owner_id ORDER BY created_at DESC, id) - 1 AS position
    FROM projects
)
UPDATE projects p
SET sort_order = ordered.position
FROM ordered
WHERE p.id = ordered.id;

CREATE INDEX idx_projects_owner_sort_order
    ON projects (owner_id, sort_order);
