-- PAID 플랜의 만료 시각. NULL은 "만료 없음"을 뜻한다 — 업그레이드 코드로 전환된 기존 PAID
-- 계정은 NULL로 남아 강등 스케줄러(만료 후보 조회) 대상이 되지 않는다.
ALTER TABLE users ADD COLUMN plan_expires_at TIMESTAMPTZ;

CREATE INDEX idx_users_plan_expires_at_downgrade
    ON users (plan_expires_at)
    WHERE plan_expires_at IS NOT NULL;
