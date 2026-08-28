-- 회전된 refresh 행을 남겨 재사용(탈취)과 "없는 토큰"을 구분한다.
ALTER TABLE refresh_tokens ADD COLUMN replaced_at TIMESTAMP WITH TIME ZONE;
