package com.history.backend.graph.service;

import java.util.List;
import java.util.UUID;

import com.history.backend.common.error.BadGatewayException;
import com.history.backend.graph.dto.AiEngineSubgraphRequest;
import com.history.backend.graph.dto.EvidenceRef;
import com.history.backend.graph.dto.GraphActivityResponse;
import com.history.backend.graph.dto.GraphConstellationResponse;
import com.history.backend.graph.dto.GraphBuildStatusResponse;
import com.history.backend.graph.dto.GraphResponse;
import com.history.backend.graph.dto.GraphSearchResponse;
import com.history.backend.graph.dto.GraphSubgraphResponse;
import com.history.backend.integration.domain.IntegrationProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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

    // 성좌 뷰용 조회 — 작업 단위는 전량, 위성만 최신 limit개. 별성이 빠지면 그림이 틀리기 때문이다.
    public GraphConstellationResponse fetchConstellation(UUID projectId, Integer limit) {
        try {
            GraphConstellationResponse response = aiEngineRestClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/graph/constellation")
                                .queryParam("project_id", projectId);
                        if (limit != null) {
                            uriBuilder.queryParam("limit", limit);
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(GraphConstellationResponse.class);
            return normalize(response);
        } catch (RestClientException exception) {
            log.error("ai-engine graph constellation request failed: {}", exception.getMessage());
            throw new BadGatewayException("Failed to load project constellation.");
        }
    }

    // 성좌 드릴인 — 작업 단위 하나의 이웃만 조회한다 (위성이 비어 있는 오래된 성좌를 채울 때).
    // nodeId는 Neo4j elementId라 콜론을 포함한다 — URI 템플릿 변수로 넘겨 strict 인코딩되게 한다.
    public GraphResponse fetchWorkUnit(UUID projectId, String nodeId) {
        try {
            GraphResponse response = aiEngineRestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/graph/work-unit")
                            .queryParam("project_id", projectId)
                            .queryParam("node_id", "{nodeId}")
                            .build(nodeId))
                    .retrieve()
                    .body(GraphResponse.class);
            return normalize(response);
        } catch (RestClientException exception) {
            log.error("ai-engine graph work-unit request failed: {}", exception.getMessage());
            throw new BadGatewayException("Failed to load work unit neighborhood.");
        }
    }

    // 그래프 노드 키워드 검색 — full-text 인덱스로 프로젝트 전체 그래프를 검색한다 (통합 검색용).
    // q는 URI 템플릿 변수로 전달한다 — 사용자 입력의 예약 문자('+', '&' 등)까지 strict 인코딩되도록.
    public GraphSearchResponse searchNodes(UUID projectId, String q, Integer limit) {
        try {
            GraphSearchResponse response = aiEngineRestClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/graph/search")
                                .queryParam("project_id", projectId)
                                .queryParam("q", "{q}");
                        if (limit != null) {
                            uriBuilder.queryParam("limit", limit);
                        }
                        return uriBuilder.build(q);
                    })
                    .retrieve()
                    .body(GraphSearchResponse.class);
            return normalize(response);
        } catch (RestClientException exception) {
            log.error("ai-engine graph search request failed: projectId={}, {}",
                    projectId, exception.getMessage());
            throw new BadGatewayException("Failed to search project graph.");
        }
    }

    // 답변 evidence(도메인 키)로 관련 서브그래프 조회 — ai-engine이 노드 resolve + 1홉 확장을 수행한다.
    public GraphSubgraphResponse fetchSubgraph(UUID projectId, List<EvidenceRef> evidence) {
        try {
            GraphSubgraphResponse response = aiEngineRestClient.post()
                    .uri("/graph/subgraph")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AiEngineSubgraphRequest(
                            projectId.toString(),
                            evidence == null ? List.of() : evidence
                    ))
                    .retrieve()
                    .body(GraphSubgraphResponse.class);
            return normalize(response);
        } catch (RestClientException exception) {
            log.error("ai-engine graph subgraph request failed: projectId={}, {}",
                    projectId, exception.getMessage());
            throw new BadGatewayException("Failed to load related subgraph.");
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

    // 프로젝트 그래프 활동 상태 조회 — 프론트 채팅 게이팅용 (idle|collecting|building)
    public GraphActivityResponse fetchGraphActivity(UUID projectId) {
        try {
            return aiEngineRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/graph/activity")
                            .queryParam("project_id", projectId)
                            .build())
                    .retrieve()
                    .body(GraphActivityResponse.class);
        } catch (RestClientException exception) {
            log.error("ai-engine graph activity request failed: projectId={}, {}",
                    projectId, exception.getMessage());
            throw new BadGatewayException("Failed to load graph activity.");
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

    // 연동 해제 cascade — 한 소스에서 수집한 노드만 삭제한다(프로젝트·다른 소스는 유지)
    public void deleteProjectSourceGraph(UUID projectId, IntegrationProvider provider) {
        try {
            aiEngineRestClient.delete()
                    .uri("/graph/projects/{projectId}/sources/{source}", projectId, provider.value())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.error("ai-engine source graph delete failed: projectId={}, provider={}, {}",
                    projectId, provider.value(), exception.getMessage());
            throw new BadGatewayException("Failed to delete integration graph.");
        }
    }

    // ai-engine이 빈 본문/누락 필드를 줘도 프론트는 항상 nodes 배열을 받도록 보정
    private GraphSearchResponse normalize(GraphSearchResponse response) {
        if (response == null || response.nodes() == null) {
            return GraphSearchResponse.empty();
        }
        return response;
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

    // 성좌 응답도 마찬가지로 프론트가 항상 배열을 받도록 보정한다.
    private GraphConstellationResponse normalize(GraphConstellationResponse response) {
        if (response == null) {
            return GraphConstellationResponse.empty();
        }
        return new GraphConstellationResponse(
                response.nodes() == null ? List.of() : response.nodes(),
                response.edges() == null ? List.of() : response.edges(),
                response.workUnitIds() == null ? List.of() : response.workUnitIds()
        );
    }

    // 누락 필드 보정 — seeds의 개별 null(미해석 evidence)은 보존하고, 필드 전체 누락만 빈 배열로 채운다
    private GraphSubgraphResponse normalize(GraphSubgraphResponse response) {
        if (response == null) {
            return GraphSubgraphResponse.empty();
        }
        return new GraphSubgraphResponse(
                response.nodes() == null ? List.of() : response.nodes(),
                response.edges() == null ? List.of() : response.edges(),
                response.seeds() == null ? List.of() : response.seeds()
        );
    }
}
