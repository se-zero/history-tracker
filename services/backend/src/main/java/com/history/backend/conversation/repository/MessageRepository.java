package com.history.backend.conversation.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.conversation.domain.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("""
            SELECT message
            FROM Message message
            WHERE message.conversation.id = :conversationId
            ORDER BY message.createdAt ASC, message.id ASC
            """)
    List<Message> findAllByConversation_IdOrderByCreatedAtAsc(@Param("conversationId") UUID conversationId);

    // 표시용 최신 메시지 페이지 (createdAt·id 역순, Pageable로 개수 제한)
    @Query("""
            SELECT message
            FROM Message message
            WHERE message.conversation.id = :conversationId
            ORDER BY message.createdAt DESC, message.id DESC
            """)
    List<Message> findLatest(@Param("conversationId") UUID conversationId, Pageable pageable);

    // 표시용 older 페이지 — (createdAt, id) 키셋 커서 이전 메시지만 역순 조회
    @Query("""
            SELECT message
            FROM Message message
            WHERE message.conversation.id = :conversationId
              AND (message.createdAt < :beforeCreatedAt
                   OR (message.createdAt = :beforeCreatedAt AND message.id < :beforeId))
            ORDER BY message.createdAt DESC, message.id DESC
            """)
    List<Message> findOlderBefore(
            @Param("conversationId") UUID conversationId,
            @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeId") UUID beforeId,
            Pageable pageable
    );

    // 대화 검색 스니펫용 — 대화별 가장 최근 매치 메시지 1건 (window로 대화당 top-1을 한 번에 조회).
    // pattern 규약은 ConversationRepository.searchPageByProject와 동일.
    // alias는 Postgres가 소문자로 접지 않도록 인용해 projection getter와 정확히 맞춘다.
    @Query(value = """
            SELECT conversation_id AS "conversationId", content AS "content"
            FROM (
                SELECT m.conversation_id, m.content,
                       ROW_NUMBER() OVER (
                           PARTITION BY m.conversation_id
                           ORDER BY m.created_at DESC, m.id DESC
                       ) AS match_rank
                FROM messages m
                WHERE m.conversation_id IN (:conversationIds)
                  AND LOWER(m.content) LIKE :pattern ESCAPE '!'
            ) ranked
            WHERE match_rank = 1
            """, nativeQuery = true)
    List<MessageMatchRow> findLatestMatchPerConversation(
            @Param("conversationIds") List<UUID> conversationIds,
            @Param("pattern") String pattern
    );

    // 대화 검색 매치 행 projection
    interface MessageMatchRow {
        UUID getConversationId();

        String getContent();
    }

    @Query("""
            SELECT message
            FROM Message message
            WHERE message.conversation.id = :conversationId
              AND message.createdAt >= (
                  SELECT cursor.createdAt
                  FROM Message cursor
                  WHERE cursor.id = :cursorMessageId
                    AND cursor.conversation.id = :conversationId
              )
            ORDER BY message.createdAt ASC, message.id ASC
            """)
    List<Message> findAllFromCursor(
            @Param("conversationId") UUID conversationId,
            @Param("cursorMessageId") UUID cursorMessageId
    );
}
