package com.history.backend.conversation.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.domain.MessageRole;
import com.history.backend.conversation.dto.AiEngineHistoryMessage;
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

    private static final String ERROR_TYPE_KEY = "error_type";
    private static final String AI_ENGINE_ERROR = "AI_ENGINE_ERROR";
    private static final Map<String, Object> AI_ENGINE_ERROR_METADATA = Map.of(
            "fallback", true,
            ERROR_TYPE_KEY, AI_ENGINE_ERROR
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
        PendingQuery pendingQuery = transactionTemplate.execute(status -> {
            Conversation conversation = findConversation(projectId, conversationId);
            // history와 question의 현재 질문 중복 방지를 위한 USER 저장 전 이력 캡처
            List<AiEngineHistoryMessage> history = loadHistory(conversationId);
            Message userMessage = appendUserMessageInCurrentTransaction(conversation, normalizedContent);
            return new PendingQuery(userMessage, history);
        });
        Message assistantMessage = appendAssistantMessageAfterQuery(
                projectId,
                conversationId,
                normalizedContent,
                pendingQuery.history()
        );
        return new MessageExchange(pendingQuery.userMessage(), assistantMessage);
    }

    // 호출자 트랜잭션 안에서만 실행 (대화 저장과 메시지 저장의 원자성 보장)
    @Transactional(propagation = Propagation.MANDATORY)
    public Message appendUserMessageInCurrentTransaction(Conversation conversation, String normalizedContent) {
        Message message = messageRepository.save(Message.user(conversation, normalizedContent));
        conversation.touch();
        return message;
    }

    // AI 질의(트랜잭션 밖) 후 assistant 응답 메시지 저장
    Message appendAssistantMessageAfterQuery(
            UUID projectId,
            UUID conversationId,
            String normalizedContent,
            List<AiEngineHistoryMessage> history
    ) {
        AiEngineQueryResult queryResult = aiEngineQueryClient.ask(normalizedContent, history);
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

    private List<AiEngineHistoryMessage> loadHistory(UUID conversationId) {
        return messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(conversationId).stream()
                .filter(this::isHistoryMessage)
                .map(message -> new AiEngineHistoryMessage(
                        message.getRole() == MessageRole.USER ? "user" : "assistant",
                        message.getContent()
                ))
                .toList();
    }

    private boolean isHistoryMessage(Message message) {
        if (message.getRole() == MessageRole.SYSTEM || message.getContent() == null || message.getContent().isBlank()) {
            return false;
        }
        return !isAiEngineError(message);
    }

    private boolean isAiEngineError(Message message) {
        Map<String, Object> metadata = message.getMetadata();
        return metadata != null && AI_ENGINE_ERROR.equals(metadata.get(ERROR_TYPE_KEY));
    }

    // fallback 응답은 metadata로 표시해 클라이언트가 오류 응답임을 구분
    private Map<String, Object> metadataFor(AiEngineQueryResult queryResult) {
        return queryResult.fallback() ? AI_ENGINE_ERROR_METADATA : null;
    }

    private record PendingQuery(
            Message userMessage,
            List<AiEngineHistoryMessage> history
    ) {
    }
}
