package com.history.backend.conversation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.history.backend.auth.service.PlanService;
import com.history.backend.common.error.BadRequestException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.conversation.ChatMemoryProperties;
import com.history.backend.conversation.domain.Conversation;
import com.history.backend.conversation.domain.Message;
import com.history.backend.conversation.domain.MessageRole;
import com.history.backend.conversation.dto.AiEngineHistoryMessage;
import com.history.backend.conversation.dto.AiEnginePriorEvidence;
import com.history.backend.conversation.dto.Cursor;
import com.history.backend.conversation.repository.ConversationRepository;
import com.history.backend.conversation.repository.MessageRepository;
import com.history.backend.graph.dto.EvidenceRef;
import com.history.backend.project.service.ProjectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Slf4j
public class MessageService {

    private static final int MESSAGE_PAGE_SIZE = 30;
    // focus 노드는 user 입력이 그대로 ai-engine system 메시지에 실리므로 경계에서 방어한다.
    private static final Set<String> ALLOWED_FOCUS_TYPES = Set.of("commit", "pull_request", "issue", "message");
    private static final int MAX_FOCUS_ID_LENGTH = 200;
    private static final String ERROR_TYPE_KEY = "error_type";
    private static final String AI_ENGINE_ERROR = "AI_ENGINE_ERROR";
    private static final String STRUCTURED_KEY = "structured";
    private static final Map<String, Object> AI_ENGINE_ERROR_METADATA = Map.of(
            "fallback", true,
            ERROR_TYPE_KEY, AI_ENGINE_ERROR
    );

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final ProjectService projectService;
    private final PlanService planService;
    private final AiEngineQueryClient aiEngineQueryClient;
    private final ChatMemoryProperties chatMemoryProperties;
    private final SummaryBackoffTracker summaryBackoffTracker;
    private final TaskExecutor summaryTaskExecutor;
    private final TransactionTemplate transactionTemplate;

    // lombok @RequiredArgsConstructor는 @Qualifier를 생성자 파라미터에 복사하지 못해 명시 생성자를 쓴다.
    public MessageService(
            MessageRepository messageRepository,
            ConversationRepository conversationRepository,
            ProjectService projectService,
            PlanService planService,
            AiEngineQueryClient aiEngineQueryClient,
            ChatMemoryProperties chatMemoryProperties,
            SummaryBackoffTracker summaryBackoffTracker,
            @Qualifier("summaryTaskExecutor") TaskExecutor summaryTaskExecutor,
            TransactionTemplate transactionTemplate
    ) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.projectService = projectService;
        this.planService = planService;
        this.aiEngineQueryClient = aiEngineQueryClient;
        this.chatMemoryProperties = chatMemoryProperties;
        this.summaryBackoffTracker = summaryBackoffTracker;
        this.summaryTaskExecutor = summaryTaskExecutor;
        this.transactionTemplate = transactionTemplate;
    }

    // 사용자 메시지 저장 → AI 질의 → 응답 저장 (트랜잭션 2단계 분리)
    // focusEvidence: 관련 그래프에서 지정한 focus 노드. 현재 턴 한정이라 history/영속화에 넣지 않고 질의로만 전달.
    public MessageExchange addMessage(
            UUID userId,
            UUID projectId,
            UUID conversationId,
            String content,
            List<EvidenceRef> focusEvidence
    ) {
        String normalizedContent = normalizeContent(content);
        // 무료 티어 질의 한도(총 10회) 검증 — 불필요한 프로젝트·대화 조회를 막기 위해 진입부에서 가장 먼저 확인한다
        planService.ensureQueryAllowed(userId);
        projectService.getProject(userId, projectId);
        // 느린 AI 질의 중 커넥션 점유를 피하고, 질의 실패와 무관하게 사용자 메시지를 보존
        PendingQuery pendingQuery = transactionTemplate.execute(status -> {
            Conversation conversation = findConversation(projectId, conversationId);
            // history와 question의 현재 질문 중복 방지를 위한 USER 저장 전 이력 캡처
            QueryContext queryContext = loadQueryContext(conversation);
            Message userMessage = appendUserMessageInCurrentTransaction(conversation, normalizedContent);
            return new PendingQuery(userMessage, queryContext);
        });
        maybeScheduleSummaryRefresh(conversationId, pendingQuery.queryContext());
        Message assistantMessage = appendAssistantMessageAfterQuery(
                userId,
                projectId,
                conversationId,
                normalizedContent,
                pendingQuery.queryContext().history(),
                pendingQuery.queryContext().priorEvidence(),
                pendingQuery.queryContext().runningSummary(),
                sanitizeFocusEvidence(focusEvidence)
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
            UUID ownerId,
            UUID projectId,
            UUID conversationId,
            String normalizedContent,
            List<AiEngineHistoryMessage> history,
            List<AiEnginePriorEvidence> priorEvidence,
            Map<String, Object> runningSummary,
            List<EvidenceRef> focusEvidence
    ) {
        // 질의 실패·fallback이어도 ai-engine 호출 시점에 이미 사용량을 기록한다(계획된 설계)
        planService.recordQuery(ownerId);
        AiEngineQueryResult queryResult = aiEngineQueryClient.ask(
                normalizedContent,
                projectId,
                history,
                priorEvidence,
                runningSummary,
                focusEvidence
        );
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

    // 표시용 메시지 페이지 — before 없으면 최신 N개, 있으면 커서 이전 older N개 (오름차순)
    @Transactional(readOnly = true)
    public MessagePage findMessagesPage(UUID userId, UUID projectId, UUID conversationId, String before) {
        projectService.getProject(userId, projectId);
        findConversation(projectId, conversationId);
        return findMessagePageInCurrentTransaction(conversationId, before);
    }

    // 호출자 트랜잭션 안에서 실행 (ConversationService 대화 상세 조회와 공유)
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public MessagePage findMessagePageInCurrentTransaction(UUID conversationId, String before) {
        // limit+1로 조회해 추가 older 페이지 존재 여부(hasMore)를 별도 count 없이 판정
        Pageable limit = PageRequest.of(0, MESSAGE_PAGE_SIZE + 1);
        List<Message> newestFirst = before == null
                ? messageRepository.findLatest(conversationId, limit)
                : olderMessagesBefore(conversationId, before, limit);
        boolean hasMore = newestFirst.size() > MESSAGE_PAGE_SIZE;
        List<Message> page = hasMore ? newestFirst.subList(0, MESSAGE_PAGE_SIZE) : newestFirst;
        List<Message> ascending = new ArrayList<>(page);
        Collections.reverse(ascending);
        String nextCursor = hasMore
                ? new Cursor(ascending.get(0).getCreatedAt(), ascending.get(0).getId()).encode()
                : null;
        return new MessagePage(ascending, hasMore, nextCursor);
    }

    private List<Message> olderMessagesBefore(UUID conversationId, String before, Pageable limit) {
        Cursor cursor = Cursor.decode(before);
        return messageRepository.findOlderBefore(conversationId, cursor.timestamp(), cursor.id(), limit);
    }

    // 대화 검색 스니펫용 — 대화별 가장 최근 매치 메시지 본문 (호출자 트랜잭션과 공유)
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Map<UUID, String> findLatestMatchedContents(List<UUID> conversationIds, String pattern) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        return messageRepository.findLatestMatchPerConversation(conversationIds, pattern).stream()
                .collect(Collectors.toMap(
                        MessageRepository.MessageMatchRow::getConversationId,
                        MessageRepository.MessageMatchRow::getContent
                ));
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

    // focus 노드 경계 방어 — user 입력이 ai-engine system 메시지에 실리므로 알 수 없는 type·빈/과길이 id를
    // 걸러낸다(프롬프트 오염·토큰 폭증·pydantic 422 fallback 방지). 개수 상한은 요청 검증(@Size)이 담당.
    private List<EvidenceRef> sanitizeFocusEvidence(List<EvidenceRef> focusEvidence) {
        if (focusEvidence == null || focusEvidence.isEmpty()) {
            return List.of();
        }
        return focusEvidence.stream()
                .filter(ref -> ref != null
                        && ref.type() != null  // Set.of(...).contains(null)은 false가 아니라 NPE를 던진다
                        && ALLOWED_FOCUS_TYPES.contains(ref.type())
                        && ref.id() != null
                        && !ref.id().isBlank()
                        && ref.id().length() <= MAX_FOCUS_ID_LENGTH)
                .toList();
    }

    // AI 질의 컨텍스트와 새로 요약할 오래된 완성 턴 구성
    private QueryContext loadQueryContext(Conversation conversation) {
        List<HistoryTurn> completedTurns = completedHistoryTurns(loadContextMessages(conversation));
        int windowFromIndex = historyFromIndex(completedTurns);
        int historyStart = backlogCarryStartIndex(completedTurns, windowFromIndex);
        List<AiEngineHistoryMessage> history = completedTurns.subList(historyStart, completedTurns.size()).stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        List<AiEnginePriorEvidence> priorEvidence = completedTurns.isEmpty()
                ? List.of()
                : extractPriorEvidence(completedTurns.get(completedTurns.size() - 1).assistant());
        SummaryTask summaryTask = summaryTask(conversation, completedTurns.subList(0, windowFromIndex));
        return new QueryContext(history, priorEvidence, conversation.getRunningSummary(), summaryTask);
    }

    // 최신 턴부터 거꾸로 글자 수를 누적해 예산 내 턴만 history로 포함할 시작 인덱스 산출.
    // 턴은 항상 통째 단위(부분 포함 없음)이고, 최신 턴은 예산을 넘겨도 최소 1턴 보장을 위해 무조건 포함한다.
    private int historyFromIndex(List<HistoryTurn> turns) {
        if (turns.isEmpty()) {
            return 0;
        }
        int budget = chatMemoryProperties.historyBudgetChars();
        int fromIndex = turns.size() - 1;
        int cumulativeChars = turnChars(turns.get(fromIndex));
        for (int index = fromIndex - 1; index >= 0; index--) {
            int chars = turnChars(turns.get(index));
            if (cumulativeChars + chars > budget) {
                break;
            }
            cumulativeChars += chars;
            fromIndex = index;
        }
        return fromIndex;
    }

    private int turnChars(HistoryTurn turn) {
        return turn.user().getContent().length() + turn.assistant().getContent().length();
    }

    // 예산 창(windowFromIndex) 밖이지만 아직 요약 트리거를 채우지 못한 백로그 턴은 질의 payload 어디에도
    // 실리지 않는 사각지대가 된다. 이를 없애기 위해 history 앞쪽에 원문 그대로 동승시킨다. 캡은 별도 설정
    // 없이 summaryTriggerChars를 재사용한다 - 요약을 미루는 한계량과 캡이 같은 의미이기 때문이다.
    // 턴은 항상 통째 단위이며, 캡을 넘기는 턴에서 중단하고 그보다 오래된 턴도 함께 제외한다.
    private int backlogCarryStartIndex(List<HistoryTurn> turns, int windowFromIndex) {
        int cap = chatMemoryProperties.summaryTriggerChars();
        int historyStart = windowFromIndex;
        int cumulativeChars = 0;
        for (int index = windowFromIndex - 1; index >= 0; index--) {
            int chars = turnChars(turns.get(index));
            if (cumulativeChars + chars > cap) {
                break;
            }
            cumulativeChars += chars;
            historyStart = index;
        }
        return historyStart;
    }

    // 누적 요약 커서 이후의 AI 문맥 대상 메시지 조회
    private List<Message> loadContextMessages(Conversation conversation) {
        UUID cursorMessageId = conversation.getSummaryThroughMessageId();
        if (cursorMessageId == null) {
            return messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(conversation.getId());
        }
        List<Message> messages = messageRepository.findAllFromCursor(conversation.getId(), cursorMessageId);
        for (int index = 0; index < messages.size(); index++) {
            if (cursorMessageId.equals(messages.get(index).getId())) {
                // 동일 생성 시각의 커서 이전 메시지가 다시 요약되는 것을 방지
                return messages.subList(index, messages.size());
            }
        }
        // 잘못된 커서로 이후 문맥이 누락되는 것보다 전체 이력 재처리를 우선
        log.warn("running summary 커서 메시지 조회 실패 - 전체 이력으로 진행: {}", cursorMessageId);
        return messageRepository.findAllByConversation_IdOrderByCreatedAtAsc(conversation.getId());
    }

    // 커서 이후 조회 결과에서 최근 이력 밖으로 밀려난 완성 턴의 요약 작업 구성
    private SummaryTask summaryTask(Conversation conversation, List<HistoryTurn> oldTurns) {
        if (oldTurns.isEmpty()) {
            return null;
        }
        UUID throughMessageId = oldTurns.get(oldTurns.size() - 1).assistant().getId();
        if (throughMessageId == null) {
            return null;
        }
        List<AiEngineHistoryMessage> history = oldTurns.stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        return new SummaryTask(history, throughMessageId, conversation.getSummaryVersion());
    }

    // 백로그가 트리거 기준 이상이고 백오프 스킵 대상이 아니면 요약 갱신을 별도 풀에 비동기 제출.
    // 이번 턴의 ask는 항상 queryContext.runningSummary()(호출 시점에 저장돼 있던 기존 요약)를 그대로 쓴다.
    // executor 람다에는 영속 엔티티(Message/Conversation)를 캡처하지 않는다 — 트랜잭션 밖 워커 스레드에서
    // lazy 로딩·detached 엔티티 문제가 나지 않도록 SummaryTask의 DTO/UUID/long/Map만 넘긴다.
    private void maybeScheduleSummaryRefresh(UUID conversationId, QueryContext queryContext) {
        SummaryTask summaryTask = queryContext.summaryTask();
        if (summaryTask == null) {
            return;
        }
        int backlogChars = summaryTask.history().stream()
                .mapToInt(message -> message.content().length())
                .sum();
        if (backlogChars < chatMemoryProperties.summaryTriggerChars()) {
            return;
        }
        if (summaryBackoffTracker.shouldSkip(conversationId)) {
            return;
        }
        Map<String, Object> existingSummary = queryContext.runningSummary();
        try {
            summaryTaskExecutor.execute(() -> runSummaryRefresh(conversationId, existingSummary, summaryTask));
        } catch (TaskRejectedException exception) {
            // 풀 포화로 제출 자체가 거부돼도 유실이 아니다 - 커서가 전진하지 않아 다음 턴에 재평가된다.
            log.warn("요약 executor 제출 거부 - 다음 턴에 재시도: {}", exception.getMessage());
        }
    }

    // 요약 생성·갱신 전체를 방어적으로 감싼다 - SyncTaskExecutor로 실행되는 테스트를 포함해
    // 이 메서드의 예외가 addMessage 호출부로 전파되면 안 된다.
    private void runSummaryRefresh(UUID conversationId, Map<String, Object> existingSummary, SummaryTask summaryTask) {
        try {
            Map<String, Object> generatedSummary;
            try {
                generatedSummary = aiEngineQueryClient.summarize(existingSummary, summaryTask.history());
            } catch (RuntimeException exception) {
                log.warn("요약 생성 실패 - 백오프 기록: {}", exception.getMessage());
                summaryBackoffTracker.recordFailure(conversationId);
                return;
            }
            if (generatedSummary == null) {
                log.warn("요약 생성 결과 없음 - 백오프 기록: conversationId={}", conversationId);
                summaryBackoffTracker.recordFailure(conversationId);
                return;
            }
            summaryBackoffTracker.recordSuccess(conversationId);
            applyGeneratedSummary(conversationId, summaryTask, generatedSummary);
        } catch (RuntimeException exception) {
            log.error("요약 갱신 처리 중 예상치 못한 오류: {}", exception.getMessage(), exception);
        }
    }

    // CAS 갱신 - 0건(동시 전진) 또는 DB 예외는 LLM 요약 자체의 실패가 아니므로 백오프 대상이 아니다.
    private void applyGeneratedSummary(UUID conversationId, SummaryTask summaryTask, Map<String, Object> generatedSummary) {
        try {
            Integer updated = transactionTemplate.execute(status -> conversationRepository.updateRunningSummary(
                    conversationId,
                    summaryTask.expectedVersion(),
                    generatedSummary,
                    summaryTask.throughMessageId(),
                    Instant.now()
            ));
            if (updated == null || updated != 1) {
                log.warn("running summary CAS 갱신 충돌 - 다음 턴에 재시도: conversationId={}", conversationId);
            }
        } catch (RuntimeException exception) {
            log.warn("running summary 갱신 실패: {}", exception.getMessage());
        }
    }

    // 완성 턴 = 유효한 USER + 바로 뒤 유효한 ASSISTANT 쌍. fallback/blank 답변으로 끝난 턴은 제외.
    private List<HistoryTurn> completedHistoryTurns(List<Message> messages) {
        List<HistoryTurn> completedTurns = new ArrayList<>();
        Message pendingUser = null;

        for (Message message : messages) {
            if (message.getRole() == MessageRole.SYSTEM) {
                continue;
            }
            if (message.getRole() == MessageRole.USER) {
                pendingUser = isHistoryMessage(message) ? message : null;
                continue;
            }
            if (message.getRole() == MessageRole.ASSISTANT && pendingUser != null && isHistoryMessage(message)) {
                completedTurns.add(new HistoryTurn(pendingUser, message));
            }
            pendingUser = null;
        }
        return completedTurns;
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

    // AI 응답 유형별 메시지 metadata 구성
    private Map<String, Object> metadataFor(AiEngineQueryResult queryResult) {
        if (queryResult.fallback()) {
            return AI_ENGINE_ERROR_METADATA;
        }
        return queryResult.structured() == null
                ? null
                : Map.of(STRUCTURED_KEY, queryResult.structured());
    }

    // 직전 정상 응답의 구조화 근거 중 후속 질문의 대상 식별에 필요한 필드 추출
    private List<AiEnginePriorEvidence> extractPriorEvidence(Message assistantMessage) {
        Map<String, Object> metadata = assistantMessage.getMetadata();
        if (metadata == null || !(metadata.get(STRUCTURED_KEY) instanceof Map<?, ?> structured)) {
            return List.of();
        }
        if (!(structured.get("evidence") instanceof List<?> evidence)) {
            return List.of();
        }
        return evidence.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(this::toPriorEvidence)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private AiEnginePriorEvidence toPriorEvidence(Map<?, ?> evidence) {
        String type = stringValue(evidence.get("type"));
        String id = stringValue(evidence.get("id"));
        String quote = stringValue(evidence.get("quote"));
        if (type == null || id == null || quote == null) {
            return null;
        }
        return new AiEnginePriorEvidence(type, id, quote);
    }

    private String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private record PendingQuery(
            Message userMessage,
            QueryContext queryContext
    ) {
    }

    private record QueryContext(
            List<AiEngineHistoryMessage> history,
            List<AiEnginePriorEvidence> priorEvidence,
            Map<String, Object> runningSummary,
            SummaryTask summaryTask
    ) {
    }

    private record SummaryTask(
            List<AiEngineHistoryMessage> history,
            UUID throughMessageId,
            long expectedVersion
    ) {
    }

    private record HistoryTurn(
            Message user,
            Message assistant
    ) {
        private List<AiEngineHistoryMessage> messages() {
            return List.of(toHistoryMessage(user), toHistoryMessage(assistant));
        }

        private static AiEngineHistoryMessage toHistoryMessage(Message message) {
            String role = message.getRole() == MessageRole.USER ? "user" : "assistant";
            return new AiEngineHistoryMessage(role, message.getContent());
        }
    }
}
