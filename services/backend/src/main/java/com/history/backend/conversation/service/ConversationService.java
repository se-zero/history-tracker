package com.history.backend.conversation.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.dto.Cursor;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int CONVERSATION_PAGE_SIZE = 20;
    private static final int SEARCH_PAGE_SIZE = 10;
    private static final int SNIPPET_CONTEXT_BEFORE = 30;
    private static final int SNIPPET_MAX_LENGTH = 160;

    private final ConversationRepository conversationRepository;
    private final ProjectService projectService;
    private final UserService userService;
    private final MessageService messageService;
    private final ConversationTitleGenerator titleGenerator;
    private final TransactionTemplate transactionTemplate;

    // 대화 생성 + 첫 메시지 질의·응답 저장 (AI 질의는 트랜잭션 밖에서 수행)
    public ConversationStart createConversation(UUID userId, UUID projectId, String firstMessageContent) {
        String normalizedContent = normalizeFirstMessage(firstMessageContent);
        User user = userService.getActiveUser(userId);
        Project project = projectService.getProject(userId, projectId);
        InitialConversation initialConversation = transactionTemplate.execute(status -> {
            Conversation conversation = conversationRepository.save(new Conversation(
                    project,
                    user,
                    titleGenerator.fromFirstMessage(normalizedContent)
            ));
            return new InitialConversation(
                    conversation,
                    messageService.appendUserMessageInCurrentTransaction(conversation, normalizedContent)
            );
        });
        Message assistantMessage = messageService.appendAssistantMessageAfterQuery(
                projectId,
                initialConversation.conversation().getId(),
                normalizedContent,
                List.of(),
                List.of(),
                null
        );
        return new ConversationStart(
                initialConversation.conversation(),
                initialConversation.userMessage(),
                assistantMessage
        );
    }

    // 표시용 대화 목록 페이지 — cursor 없으면 첫 페이지, 있으면 (updatedAt, id) 커서 이전 older 페이지
    @Transactional(readOnly = true)
    public ConversationPage findConversationsPage(UUID userId, UUID projectId, String cursor) {
        projectService.getProject(userId, projectId);
        // limit+1로 조회해 다음 페이지 존재 여부를 별도 count 없이 판정
        Pageable limit = PageRequest.of(0, CONVERSATION_PAGE_SIZE + 1);
        List<Conversation> rows = cursor == null
                ? conversationRepository.findFirstPageByProject(projectId, limit)
                : conversationsBefore(projectId, cursor, limit);
        boolean hasMore = rows.size() > CONVERSATION_PAGE_SIZE;
        List<Conversation> page = hasMore ? rows.subList(0, CONVERSATION_PAGE_SIZE) : rows;
        String nextCursor = hasMore
                ? cursorOf(page.get(page.size() - 1))
                : null;
        return new ConversationPage(page, nextCursor);
    }

    @Transactional(readOnly = true)
    public ConversationDetail getConversationDetail(UUID userId, UUID projectId, UUID conversationId) {
        projectService.getProject(userId, projectId);
        Conversation conversation = findConversation(projectId, conversationId);
        MessagePage messages = messageService.findMessagePageInCurrentTransaction(conversationId, null);
        return new ConversationDetail(conversation, messages);
    }

    // 통합 검색 — 제목·메시지 본문 부분 일치 대화 목록 (updatedAt 역순, 매치 스니펫 포함)
    @Transactional(readOnly = true)
    public List<ConversationSearchResult> searchConversations(UUID userId, UUID projectId, String query) {
        projectService.getProject(userId, projectId);
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        String pattern = likePattern(normalized);
        List<Conversation> conversations = conversationRepository.searchPageByProject(
                projectId,
                pattern,
                PageRequest.of(0, SEARCH_PAGE_SIZE)
        );
        if (conversations.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> matchedContents = messageService.findLatestMatchedContents(
                conversations.stream().map(Conversation::getId).toList(),
                pattern
        );
        return conversations.stream()
                .map(conversation -> new ConversationSearchResult(
                        conversation,
                        snippetOf(matchedContents.get(conversation.getId()), normalized)
                ))
                .toList();
    }

    // LIKE 패턴 조립 — 와일드카드(%·_)와 escape 문자를 '!'로 이스케이프해 리터럴 부분 일치로 강제
    private String likePattern(String query) {
        String escaped = query.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    // 매치 지점 앞뒤 맥락을 포함한 한 줄 발췌. 매치 메시지가 없으면(제목만 매치) null.
    private String snippetOf(String content, String query) {
        if (content == null) {
            return null;
        }
        String flattened = content.replaceAll("\\s+", " ").trim();
        int matchIndex = flattened.toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
        // 공백 정규화로 매치 위치를 다시 못 찾는 드문 경우엔 앞에서부터 발췌
        int start = matchIndex < 0 ? 0 : Math.max(0, matchIndex - SNIPPET_CONTEXT_BEFORE);
        int end = Math.min(flattened.length(), start + SNIPPET_MAX_LENGTH);
        return (start > 0 ? "…" : "")
                + flattened.substring(start, end)
                + (end < flattened.length() ? "…" : "");
    }

    private List<Conversation> conversationsBefore(UUID projectId, String cursor, Pageable limit) {
        Cursor decoded = Cursor.decode(cursor);
        return conversationRepository.findPageByProjectBefore(projectId, decoded.timestamp(), decoded.id(), limit);
    }

    private String cursorOf(Conversation conversation) {
        return new Cursor(conversation.getUpdatedAt(), conversation.getId()).encode();
    }

    @Transactional
    public Conversation updateTitle(UUID userId, UUID projectId, UUID conversationId, String title) {
        projectService.getProject(userId, projectId);
        Conversation conversation = findConversation(projectId, conversationId);
        conversation.updateTitle(normalizeTitle(title));
        return conversation;
    }

    @Transactional
    public void deleteConversation(UUID userId, UUID projectId, UUID conversationId) {
        projectService.getProject(userId, projectId);
        Conversation conversation = findConversation(projectId, conversationId);
        conversationRepository.delete(conversation);
    }

    private Conversation findConversation(UUID projectId, UUID conversationId) {
        return conversationRepository.findByIdAndProject_Id(conversationId, projectId)
                .orElseThrow(() -> new NotFoundException("Conversation not found."));
    }

    private String normalizeTitle(String title) {
        // 빈 제목은 null로 저장해 제목 제거로 처리
        if (title == null || title.isBlank()) {
            return null;
        }
        return title.trim();
    }

    private String normalizeFirstMessage(String firstMessageContent) {
        if (firstMessageContent == null || firstMessageContent.isBlank()) {
            throw new BadRequestException("Message content is required.");
        }
        return firstMessageContent.trim();
    }

    private record InitialConversation(Conversation conversation, Message userMessage) {
    }
}
