CREATE TABLE webhook_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id TEXT NOT NULL,
    project_id UUID REFERENCES projects (id) ON DELETE SET NULL,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS',
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    CONSTRAINT chk_webhook_deliveries_status
        CHECK (status IN ('IN_PROGRESS', 'PROCESSED', 'FAILED'))
);

CREATE UNIQUE INDEX uq_webhook_deliveries_delivery_id
    ON webhook_deliveries (delivery_id);

CREATE INDEX idx_webhook_deliveries_project_received_at
    ON webhook_deliveries (project_id, received_at DESC)
    WHERE project_id IS NOT NULL;

CREATE TABLE checkpoints (
    project_id UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    cursor_key TEXT NOT NULL,
    cursor_value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, provider, cursor_key),
    CONSTRAINT chk_checkpoints_provider
        CHECK (provider IN ('github', 'jira', 'slack'))
);
