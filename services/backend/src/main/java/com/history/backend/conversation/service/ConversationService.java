package com.history.backend.conversation.service;

import java.util.List;
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
