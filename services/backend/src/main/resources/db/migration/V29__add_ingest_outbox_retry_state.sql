-- 아웃박스 릴레이의 실패 행 격리 재시도 상태. attempts는 릴레이가 이 행의 발행에 실패한 횟수로, 0이면 실패한 적
-- 없는 행이라 묶음으로 나가고 1 이상이면 next_attempt_at이 지난 뒤 한 건씩 따로 재시도된다.
-- 상태를 메모리가 아니라 행에 두는 이유: 프로젝트 삭제·연동 해제로 행이 CASCADE로 지워질 때 상태가 함께 사라지고,
-- worker를 재시작해도 유지된다. 인덱스는 추가하지 않는다 — 미발행 행은 평소 쌓이지 않아 기존 부분 인덱스
-- idx_ingest_outbox_unpublished로 충분하다. 두 열 모두 기본값(0·NULL)이 있어 worker만 이전 버전으로 되돌려도 동작한다.
ALTER TABLE ingest_outbox
    ADD COLUMN attempts        INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ;
