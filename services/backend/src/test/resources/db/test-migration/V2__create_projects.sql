CREATE TABLE projects (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    name TEXT NOT NULL,
    -- H2 2.4 does not accept expression indexes like lower(name), so mirror that key with a generated column.
    name_key TEXT GENERATED ALWAYS AS (lower(name)),
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_projects_owner_id
    ON projects (owner_id);

CREATE UNIQUE INDEX uq_projects_owner_name_active
    ON projects (owner_id, name_key);
