package com.history.backend.conversation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.conversation.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findAllByProject_IdOrderByUpdatedAtDesc(UUID projectId);

    Optional<Conversation> findByIdAndProject_Id(UUID id, UUID projectId);

    // 요약 전용 버전 비교를 통한 동시 갱신 충돌 방지
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Conversation conversation
            SET conversation.runningSummary = :runningSummary,
                conversation.summaryThroughMessageId = :throughMessageId,
                conversation.summaryUpdatedAt = :updatedAt,
                conversation.summaryVersion = conversation.summaryVersion + 1
            WHERE conversation.id = :conversationId
              AND conversation.summaryVersion = :expectedVersion
            """)
    int updateRunningSummary(
            @Param("conversationId") UUID conversationId,
            @Param("expectedVersion") long expectedVersion,
            @Param("runningSummary") Map<String, Object> runningSummary,
            @Param("throughMessageId") UUID throughMessageId,
            @Param("updatedAt") Instant updatedAt
    );
}
