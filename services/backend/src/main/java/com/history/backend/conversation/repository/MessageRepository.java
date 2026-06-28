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
