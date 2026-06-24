package com.history.backend.graph.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.graph.dto.GraphBuildStatusResponse;
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

    // 후처리(Layer 4) 빌드를 프로젝트 단위로 트리거 — ai-engine이 백그라운드로 실행하고 202 + 현재 상태를 반환한다.
    // 길게 걸리는 빌드를 동기 블로킹하지 않으므로, 진행 상황은 fetchBuildStatus로 폴링한다.
    public GraphBuildStatusResponse triggerBuild(UUID projectId, boolean verify) {
        try {
            return aiEngineRestClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/graph/build")
                            .queryParam("project_id", projectId)
                            .queryParam("verify", verify)
                            .build())
                    .retrieve()
                    .body(GraphBuildStatusResponse.class);
        } catch (RestClientException exception) {
            log.error("ai-engine graph build request failed: projectId={}, {}", projectId, exception.getMessage());
            throw new BadGatewayException("Failed to rebuild project graph.");
        }
    }

    // 프로젝트의 현재 빌드 상태 조회 — 트리거(202) 후 완료까지 폴링한다.
    public GraphBuildStatusResponse fetchBuildStatus(UUID projectId) {
        try {
            return aiEngineRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/graph/build/status")
                            .queryParam("project_id", projectId)
                            .build())
                    .retrieve()
                    .body(GraphBuildStatusResponse.class);
        } catch (RestClientException exception) {
            log.error("ai-engine graph build status request failed: projectId={}, {}",
                    projectId, exception.getMessage());
            throw new BadGatewayException("Failed to load graph build status.");
        }
    }

    // 프로젝트 그래프 삭제 — ai-engine에 project_id 서브그래프 삭제 요청 (멱등)
    public void deleteProjectGraph(UUID projectId) {
        try {
            aiEngineRestClient.delete()
                    .uri("/graph/projects/{projectId}", projectId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.error("ai-engine project graph delete failed: projectId={}, {}",
                    projectId, exception.getMessage());
            throw new BadGatewayException("Failed to delete project graph.");
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
