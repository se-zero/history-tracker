CREATE TABLE integrations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    external_ref JSONB NOT NULL,
    installation_id UUID REFERENCES github_installations (id) ON DELETE RESTRICT,
    encrypted_credential BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_integrations_provider
        CHECK (provider IN ('github', 'slack', 'jira')),
    CONSTRAINT chk_integrations_provider_credentials
        CHECK (
            (
                provider = 'github'
                AND installation_id IS NOT NULL
                AND encrypted_credential IS NULL
            )
            OR
            (
                provider IN ('slack', 'jira')
                AND installation_id IS NULL
                AND encrypted_credential IS NOT NULL
            )
        )
);

CREATE UNIQUE INDEX uq_integrations_project_provider
    ON integrations (project_id, provider);

CREATE INDEX idx_integrations_installation_id
    ON integrations (installation_id)
    WHERE installation_id IS NOT NULL;

CREATE INDEX idx_integrations_github_repository_full_name
    ON integrations ((external_ref->>'repository_full_name'))
    WHERE provider = 'github';
