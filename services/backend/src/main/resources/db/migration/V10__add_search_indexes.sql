-- 통합 검색(대화 제목·메시지 본문 부분 일치)용 trigram 인덱스.
-- 검색 쿼리가 LOWER(...) LIKE '%q%' 형태라 인덱스도 같은 LOWER 식이어야 탄다.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_conversations_title_trgm
    ON conversations USING GIN (LOWER(title) gin_trgm_ops);

CREATE INDEX idx_messages_content_trgm
    ON messages USING GIN (LOWER(content) gin_trgm_ops);
