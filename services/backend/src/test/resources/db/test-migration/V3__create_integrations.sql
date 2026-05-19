CREATE TABLE integrations (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    external_ref JSON NOT NULL,
    installation_id UUID REFERENCES github_installations (id) ON DELETE RESTRICT,
    encrypted_credential BYTEA,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
    ON integrations (installation_id);
