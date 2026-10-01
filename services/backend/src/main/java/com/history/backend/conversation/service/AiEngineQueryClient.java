package com.history.backend.conversation.service;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.history.backend.conversation.dto.AiEngineHistoryMessage;
import com.history.backend.conversation.dto.AiEnginePriorEvidence;
import com.history.backend.conversation.dto.AiEngineQueryRequest;
import com.history.backend.conversation.dto.AiEngineQueryResponse;
import com.history.backend.conversation.dto.AiEngineSummaryRequest;
import com.history.backend.conversation.dto.AiEngineSummaryResponse;
import com.history.backend.graph.dto.EvidenceRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// ai-engine GraphRAG 질의 클라이언트
@Slf4j
@Service
@RequiredArgsConstructor
public class AiEngineQueryClient {

    private static final String FALLBACK_ANSWER = "질문을 처리하는 중 오류가 발생했습니다.";

    private final RestClient aiEngineRestClient;

    // ai-engine 질의 — 실패 시 예외 대신 fallback 답변 반환 (대화 흐름 유지)
    // projectId로 그래프 조회가 스코프된다 (다른 프로젝트 데이터 인용 차단)
    public AiEngineQueryResult ask(
            String question,
            UUID projectId,
            List<AiEngineHistoryMessage> history,
            List<AiEnginePriorEvidence> priorEvidence,
            Map<String, Object> runningSummary,
            List<EvidenceRef> focusEvidence
    ) {
        try {
            AiEngineQueryResponse response = aiEngineRestClient.post()
                    .uri("/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiEngineQueryRequest(
                            question,
                            projectId.toString(),
                            history,
                            priorEvidence,
                            runningSummary,
                            focusEvidence == null ? List.of() : focusEvidence
                    ))
                    .retrieve()
                    .body(AiEngineQueryResponse.class);
            String answer = normalizeAnswer(response);
            if (answer.isBlank()) {
                return AiEngineQueryResult.fallback(FALLBACK_ANSWER);
            }
            return AiEngineQueryResult.success(answer, response.structured());
        } catch (RestClientException exception) {
            log.error("ai-engine query request failed: {}", exception.getMessage());
            if (isReadTimeout(exception)) {
                return AiEngineQueryResult.timeout(FALLBACK_ANSWER);
            }
            return AiEngineQueryResult.fallback(FALLBACK_ANSWER);
        }
    }

    // 연결·읽기 시간 초과는 둘 다 SocketTimeoutException이고 메시지만 다르다("Connect timed out" /
    // "Read timed out"). 연결 단계에서 끊긴 것은 답을 기다리다 끊긴 것이 아니므로 시간 초과로 보지 않는다.
    private boolean isReadTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException timeout) {
                String message = timeout.getMessage();
                return message == null || !message.toLowerCase(Locale.ROOT).contains("connect");
            }
        }
        return false;
    }

    //  누적 요약 갱신을 위한 ai-engine 병합 요청 (기존 요약 + 추가 대화 턴)
    public Map<String, Object> summarize(
            Map<String, Object> runningSummary,
            List<AiEngineHistoryMessage> history
    ) {
        try {
            AiEngineSummaryResponse response = aiEngineRestClient.post()
                    .uri("/query/summary")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiEngineSummaryRequest(runningSummary, history))
                    .retrieve()
                    .body(AiEngineSummaryResponse.class);
            return response == null ? null : response.summary();
        } catch (RestClientException exception) {
            log.warn("ai-engine summary request failed: {}", exception.getMessage());
            return null;
        }
    }

    private String normalizeAnswer(AiEngineQueryResponse response) {
        if (response == null || response.answer() == null) {
            return "";
        }
        return response.answer().trim();
    }
}
