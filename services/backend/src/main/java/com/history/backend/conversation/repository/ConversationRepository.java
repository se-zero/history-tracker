package com.history.backend.conversation.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.conversation.domain.Conversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    // 표시용 대화 목록 첫 페이지 (updatedAt·id 역순, Pageable로 개수 제한)
    @Query("""
            SELECT conversation
            FROM Conversation conversation
            WHERE conversation.project.id = :projectId
            ORDER BY conversation.updatedAt DESC, conversation.id DESC
            """)
    List<Conversation> findFirstPageByProject(@Param("projectId") UUID projectId, Pageable pageable);

    // 표시용 older 페이지 — (updatedAt, id) 키셋 커서 이전 대화만 역순 조회
    @Query("""
            SELECT conversation
            FROM Conversation conversation
            WHERE conversation.project.id = :projectId
              AND (conversation.updatedAt < :beforeUpdatedAt
                   OR (conversation.updatedAt = :beforeUpdatedAt AND conversation.id < :beforeId))
            ORDER BY conversation.updatedAt DESC, conversation.id DESC
            """)
    List<Conversation> findPageByProjectBefore(
            @Param("projectId") UUID projectId,
            @Param("beforeUpdatedAt") Instant beforeUpdatedAt,
            @Param("beforeId") UUID beforeId,
            Pageable pageable
    );

    Optional<Conversation> findByIdAndProject_Id(UUID id, UUID projectId);

    // 통합 검색 — 제목 또는 메시지 본문이 매치되는 대화 (updatedAt·id 역순, Pageable로 개수 제한).
    // pattern은 서비스가 소문자화·와일드카드 이스케이프('!')·양쪽 % 처리를 끝낸 LIKE 패턴이다.
    @Query("""
            SELECT conversation
            FROM Conversation conversation
            WHERE conversation.project.id = :projectId
              AND (LOWER(conversation.title) LIKE :pattern ESCAPE '!'
                   OR EXISTS (
                       SELECT 1
                       FROM Message message
                       WHERE message.conversation = conversation
                         AND LOWER(message.content) LIKE :pattern ESCAPE '!'
                   ))
            ORDER BY conversation.updatedAt DESC, conversation.id DESC
            """)
    List<Conversation> searchPageByProject(
            @Param("projectId") UUID projectId,
            @Param("pattern") String pattern,
            Pageable pageable
    );

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
