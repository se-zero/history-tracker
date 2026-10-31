-- pipeline-worker 수집 발행 아웃박스. 수집기는 브로커 대신 이 표에 쓰고, worker 안의 릴레이가 id 순으로 읽어
-- RabbitMQ로 옮긴 뒤 published_at을 채운다. 두 FK 모두 CASCADE — 프로젝트 삭제·연동 해제 때 미발행 행이 함께
-- 사라져야 지운 그래프를 되살리지 않는다(CollectionRunContext.ensureActive와 같은 의도). JPA 엔티티 없음.
-- payload가 TEXT인 이유: diff 전문의 NUL을 Jackson이 \u0000 리터럴로 쓰는데 jsonb는 이를 거부한다.
CREATE TABLE ingest_outbox (
    id             BIGSERIAL   PRIMARY KEY,
    project_id     UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    integration_id UUID        REFERENCES integrations (id) ON DELETE CASCADE,
    source         TEXT        NOT NULL,
    payload        TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
CREATE INDEX idx_ingest_outbox_unpublished  ON ingest_outbox (id)           WHERE published_at IS NULL;
CREATE INDEX idx_ingest_outbox_published_at ON ingest_outbox (published_at) WHERE published_at IS NOT NULL;
