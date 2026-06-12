package com.history.backend.graph.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.graph.dto.GraphResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// 그래프 데이터의 단일 소유자는 ai-engine(Neo4j). backend는 인가 통과 후 조회를 프록시한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AiEngineGraphClient {

    private final RestClient aiEngineRestClient;

    public GraphResponse fetchOverview(UUID projectId, Integer limit, String types) {
        try {
            GraphResponse response = aiEngineRestClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/graph/overview")
                                .queryParam("project_id", projectId);
                        if (limit != null) {
                            uriBuilder.queryParam("limit", limit);
                        }
                        if (types != null && !types.isBlank()) {
                            uriBuilder.queryParam("types", types);
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(GraphResponse.class);
            return normalize(response);
        } catch (RestClientException exception) {
            log.error("ai-engine graph overview request failed: {}", exception.getMessage());
            throw new BadGatewayException("Failed to load project graph.");
        }
    }

    // ai-engine이 빈 본문/누락 필드를 줘도 프론트는 항상 nodes/edges 배열을 받도록 보정
    private GraphResponse normalize(GraphResponse response) {
        if (response == null) {
            return GraphResponse.empty();
        }
        return new GraphResponse(
                response.nodes() == null ? List.of() : response.nodes(),
                response.edges() == null ? List.of() : response.edges()
        );
    }
}
