-- 가입 시 약관 동의 기록. 둘 다 nullable — 기존 사용자는 NULL로 남아 다음 로그인 때 동의 화면을 보게 된다.
ALTER TABLE users ADD COLUMN consent_terms_version TEXT;
ALTER TABLE users ADD COLUMN consent_recorded_at TIMESTAMP WITH TIME ZONE;
