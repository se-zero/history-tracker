-- 웹훅 하나가 같은 레포를 연결한 여러 프로젝트로 팬아웃되므로 claim 단위를 (delivery_id, project_id)로 바꾼다.
-- 기존 행은 delivery_id가 이미 유일해 충돌 없음. project_id는 nullable 유지 — 워커는 항상 값을 넣는다.
DROP INDEX uq_webhook_deliveries_delivery_id;
CREATE UNIQUE INDEX uq_webhook_deliveries_delivery_project
    ON webhook_deliveries (delivery_id, project_id);
