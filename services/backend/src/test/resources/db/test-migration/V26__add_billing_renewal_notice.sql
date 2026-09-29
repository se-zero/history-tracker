-- H2 테스트 DB. TIMESTAMPTZ는 TIMESTAMP WITH TIME ZONE으로 바꾼다.
ALTER TABLE billing_subscriptions
    ADD COLUMN renewal_notice_period_end TIMESTAMP WITH TIME ZONE;
