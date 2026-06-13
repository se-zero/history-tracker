package com.history.backend.conversation.repository;

import java.util.List;
import java.util.UUID;

import com.history.backend.conversation.domain.Message;
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
