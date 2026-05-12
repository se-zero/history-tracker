package com.history.backend.service;

import com.history.backend.dto.QueryRequest;
import com.history.backend.dto.QueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    private final RestClient aiEngineRestClient;

    public QueryResponse ask(String projectId, QueryRequest request) {
        try {
            QueryResponse response = aiEngineRestClient.post()
                    .uri("/query")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(QueryResponse.class);
            return response != null ? response : new QueryResponse("");
        } catch (RestClientException e) {
            log.error("ai-engine 호출 실패: {}", e.getMessage());
            return new QueryResponse("질문을 처리하는 중 오류가 발생했습니다.");
        }
    }
}

