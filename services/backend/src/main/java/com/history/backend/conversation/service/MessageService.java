package com.history.backend.conversation.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.conversation.repository.MessageRepository;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class MessageService {

    private static final Map<String, Object> AI_ENGINE_ERROR_METADATA = Map.of(
            "fallback", true,
            "error_type", "AI_ENGINE_ERROR"
    );

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ProjectService projectService;
    private final AiEngineQueryClient aiEngineQueryClient;
    private final TransactionTemplate transactionTemplate;

    public MessageExchange addMessage(UUID userId, UUID projectId, UUID conversationId, String content) {
        String normalizedContent = normalizeContent(content);
        projectService.getProject(userId, projectId);
        Message userMessage = transactionTemplate.execute(status -> {
            Conversation conversation = findConversation(projectId, conversationId);
            return appendUserMessageInCurrentTransaction(conversation, normalizedContent);
        });
        Message assistantMessage = appendAssistantMessageAfterQuery(projectId, conversationId, normalizedContent);
        return new MessageExchange(userMessage, assistantMessage);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Message appendUserMessageInCurrentTransaction(Conversation conversation, String normalizedContent) {
        Message message = messageRepository.save(Message.user(conversation, normalizedContent));
        conversation.touch();
        return message;
    }

    Message appendAssistantMessageAfterQuery(UUID projectId, UUID conversationId, String normalizedContent) {
        AiEngineQueryResult queryResult = aiEngineQueryClient.ask(normalizedContent);
        return transactionTemplate.execute(status -> {
            Conversation conversation = findConversation(projectId, conversationId);
            Message assistantMessage = messageRepository.save(Message.assistant(
                    conversation,
                    queryResult.answer(),
                    metadataFor(queryResult)
            ));
            conversation.touch();
            return assistantMessage;
        });
    }

    @Transactional(readOnly = true)
    public List<Message> findMessages(UUID userId, UUID projectId, UUID conversationId) {
        projectService.getProject(userId, projectId);
        findConversation(projectId, conversationId);
        return messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(conversationId);
    }

    private Conversation findConversation(UUID projectId, UUID conversationId) {
        return conversationRepository.findByIdAndProject_Id(conversationId, projectId)
                .orElseThrow(() -> new NotFoundException("Conversation not found."));
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestException("Message content is required.");
        }
        return content.trim();
    }

    private Map<String, Object> metadataFor(AiEngineQueryResult queryResult) {
        return queryResult.fallback() ? AI_ENGINE_ERROR_METADATA : null;
    }
}
