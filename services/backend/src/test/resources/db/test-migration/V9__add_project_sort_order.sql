-- main V9의 스키마 부분만 미러링(H2). 테스트 DB는 마이그레이션 시점에 projects가 비어
-- 있어 기존 행 backfill(WITH ... ROW_NUMBER)은 생략한다.
ALTER TABLE projects
    ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_projects_owner_sort_order
    ON projects (owner_id, sort_order);
