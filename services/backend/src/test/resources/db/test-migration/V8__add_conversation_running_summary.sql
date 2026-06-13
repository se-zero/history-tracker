ALTER TABLE conversations ADD COLUMN running_summary JSON;
ALTER TABLE conversations ADD COLUMN summary_through_message_id UUID;
ALTER TABLE conversations ADD COLUMN summary_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE conversations ADD COLUMN summary_version BIGINT NOT NULL DEFAULT 0;
