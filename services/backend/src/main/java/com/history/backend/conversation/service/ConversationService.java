package com.history.backend.conversation.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.project.domain.Project;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ProjectService projectService;
    private final UserService userService;
    private final MessageService messageService;
    private final ConversationTitleGenerator titleGenerator;
    private final TransactionTemplate transactionTemplate;

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
                normalizedContent
        );
        return new ConversationStart(
                initialConversation.conversation(),
                initialConversation.userMessage(),
                assistantMessage
        );
    }

    @Transactional(readOnly = true)
    public List<Conversation> findConversations(UUID userId, UUID projectId) {
        projectService.getProject(userId, projectId);
        return conversationRepository.findAllByProject_IdOrderByUpdatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public Conversation getConversation(UUID userId, UUID projectId, UUID conversationId) {
        projectService.getProject(userId, projectId);
        return findConversation(projectId, conversationId);
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
