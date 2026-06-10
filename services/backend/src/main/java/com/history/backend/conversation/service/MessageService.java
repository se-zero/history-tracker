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

    // 사용자 메시지 저장 → AI 질의 → 응답 저장 (트랜잭션 2단계 분리)
    public MessageExchange addMessage(UUID userId, UUID projectId, UUID conversationId, String content) {
        String normalizedContent = normalizeContent(content);
        projectService.getProject(userId, projectId);
        // 느린 AI 질의 중 커넥션 점유를 피하고, 질의 실패와 무관하게 사용자 메시지를 보존
        Message userMessage = transactionTemplate.execute(status -> {
            Conversation conversation = findConversation(projectId, conversationId);
            return appendUserMessageInCurrentTransaction(conversation, normalizedContent);
        });
        Message assistantMessage = appendAssistantMessageAfterQuery(projectId, conversationId, normalizedContent);
        return new MessageExchange(userMessage, assistantMessage);
    }

    // 호출자 트랜잭션 안에서만 실행 (대화 저장과 메시지 저장의 원자성 보장)
    @Transactional(propagation = Propagation.MANDATORY)
    public Message appendUserMessageInCurrentTransaction(Conversation conversation, String normalizedContent) {
        Message message = messageRepository.save(Message.user(conversation, normalizedContent));
        conversation.touch();
        return message;
    }

    // AI 질의(트랜잭션 밖) 후 assistant 응답 메시지 저장
    Message appendAssistantMessageAfterQuery(UUID projectId, UUID conversationId, String normalizedContent) {
        AiEngineQueryResult queryResult = aiEngineQueryClient.ask(normalizedContent);
        return transactionTemplate.execute(status -> {
            // 트랜잭션이 분리되어 있어 질의 중 삭제됐을 수 있으므로 conversation 재조회
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
        return findMessagesInCurrentTransaction(conversationId);
    }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public List<Message> findMessagesInCurrentTransaction(UUID conversationId) {
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

    // fallback 응답은 metadata로 표시해 클라이언트가 오류 응답임을 구분
    private Map<String, Object> metadataFor(AiEngineQueryResult queryResult) {
        return queryResult.fallback() ? AI_ENGINE_ERROR_METADATA : null;
    }
}
