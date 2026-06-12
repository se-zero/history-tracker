-- H2(테스트 DB)는 부분 인덱스(WHERE 절)를 지원하지 않는다.
-- 운영(Postgres) 마이그레이션은 부분 인덱스를 쓰지만, 테스트에선 인덱스 술어가
-- 스키마 검증(ddl-auto: validate) 대상이 아니므로 전체 인덱스로 대체한다.
CREATE INDEX idx_users_deleted_at_purge
    ON users (deleted_at);
