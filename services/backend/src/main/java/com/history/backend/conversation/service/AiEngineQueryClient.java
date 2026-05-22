package com.history.backend.conversation.service;

import com.history.backend.conversation.dto.AiEngineQueryRequest;
import com.history.backend.conversation.dto.AiEngineQueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiEngineQueryClient {

    private static final String FALLBACK_ANSWER = "질문을 처리하는 중 오류가 발생했습니다.";

    private final RestClient aiEngineRestClient;

    public AiEngineQueryResult ask(String question) {
        try {
            AiEngineQueryResponse response = aiEngineRestClient.post()
                    .uri("/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiEngineQueryRequest(question))
                    .retrieve()
                    .body(AiEngineQueryResponse.class);
            return AiEngineQueryResult.success(normalizeAnswer(response));
        } catch (RestClientException exception) {
            log.error("ai-engine query request failed: {}", exception.getMessage());
            return AiEngineQueryResult.fallback(FALLBACK_ANSWER);
        }
    }

    private String normalizeAnswer(AiEngineQueryResponse response) {
        if (response == null || response.answer() == null) {
            return "";
        }
        return response.answer();
    }
}
