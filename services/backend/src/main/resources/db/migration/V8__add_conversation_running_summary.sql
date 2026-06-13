ALTER TABLE conversations
    ADD COLUMN running_summary JSONB,
    ADD COLUMN summary_through_message_id UUID,
    ADD COLUMN summary_updated_at TIMESTAMPTZ,
    ADD COLUMN summary_version BIGINT NOT NULL DEFAULT 0;
