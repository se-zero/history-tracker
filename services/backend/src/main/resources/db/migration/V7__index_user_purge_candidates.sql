CREATE INDEX idx_users_deleted_at_purge
    ON users (deleted_at)
    WHERE deleted_at IS NOT NULL;
