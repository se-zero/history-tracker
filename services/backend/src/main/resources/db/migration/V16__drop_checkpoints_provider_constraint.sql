-- V12가 integrations.provider의 열거형 CHECK를 없앤 것과 같은 이유로 checkpoints.provider도 없앤다.
-- V5가 만든 chk_checkpoints_provider(github/jira/slack)가 그대로 남아 있어, 새 provider의 checkpoint
-- upsert가 매번 제약 위반으로 실패했다(A9 — Discord 실기동에서 발견: publish는 성공하고 checkpoint
-- 쓰기만 터져 커서가 영원히 전진하지 못하는 상태로 이어졌다).
-- 유효성은 애플리케이션(CollectionProvider enum)이 보증한다.
ALTER TABLE checkpoints DROP CONSTRAINT chk_checkpoints_provider;
