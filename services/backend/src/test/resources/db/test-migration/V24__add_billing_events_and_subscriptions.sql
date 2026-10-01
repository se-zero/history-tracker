-- H2(테스트 DB)는 gen_random_uuid()/now()가 없다 — RANDOM_UUID()/CURRENT_TIMESTAMP로 대체하고
-- TIMESTAMPTZ는 TIMESTAMP WITH TIME ZONE으로 바꾼다. 나머지는 운영(Postgres) 마이그레이션과 동일하다.
CREATE TABLE billing_events (
    id UUID DEFAULT RANDOM_UUID() PRIMARY KEY,
    event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    notification_id TEXT,
    outcome TEXT NOT NULL,
    subscription_id TEXT,
    user_id UUID,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX uq_billing_events_event_id
    ON billing_events (event_id);

CREATE TABLE billing_subscriptions (
    subscription_id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    customer_id TEXT NOT NULL,
    status TEXT NOT NULL,
    price_id TEXT,
    current_period_ends_at TIMESTAMP WITH TIME ZONE,
    scheduled_change_action TEXT,
    scheduled_change_effective_at TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    last_event_occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_billing_subscriptions_user_id
    ON billing_subscriptions (user_id);
