-- Paddle 결제 알림 수신 원장. 알림 재시도(같은 event_id) 중복 처리를 막는 claim 테이블이자
-- 처리 결과(outcome)를 남기는 감사 로그다. 원본 payload는 저장하지 않는다 — customer 이벤트에
-- 이메일·이름 등 개인정보가 담겨 있어 그대로 쌓으면 안 된다.
CREATE TABLE billing_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    notification_id TEXT,
    outcome TEXT NOT NULL,
    subscription_id TEXT,
    -- FK를 걸지 않는다 — 사용자가 파기(soft-delete 후 완전 삭제)된 뒤에도 결제 원장은 남겨야 한다.
    -- users FK로 걸면 CASCADE로 원장까지 함께 사라져 감사 이력이 끊긴다.
    user_id UUID,
    received_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uq_billing_events_event_id
    ON billing_events (event_id);

-- 구독 상태 캐시. Paddle이 진실의 원천이고 이 테이블은 알림을 받을 때마다 최신 상태로 수렴하는 캐시다.
CREATE TABLE billing_subscriptions (
    subscription_id TEXT PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    customer_id TEXT NOT NULL,
    status TEXT NOT NULL,
    price_id TEXT,
    current_period_ends_at TIMESTAMPTZ,
    scheduled_change_action TEXT,
    scheduled_change_effective_at TIMESTAMPTZ,
    canceled_at TIMESTAMPTZ,
    last_event_occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_billing_subscriptions_user_id
    ON billing_subscriptions (user_id);
