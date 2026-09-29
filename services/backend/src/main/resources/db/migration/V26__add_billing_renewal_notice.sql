-- 결제일 7일 전 안내를 같은 결제 주기에 한 번만 보내기 위한 표시.
-- 값은 안내를 보낸 주기의 current_period_ends_at이다. 갱신으로 주기 끝이 바뀌면 다음 안내는 다시 보낼 수 있다.
ALTER TABLE billing_subscriptions
    ADD COLUMN renewal_notice_period_end TIMESTAMPTZ;
