package com.history.backend.graph.service;

import java.util.UUID;

import com.history.backend.graph.dto.GraphBuildStatusResponse;
import com.history.backend.graph.dto.GraphResponse;
import com.history.backend.graph.dto.GraphSearchResponse;
import com.history.backend.graph.dto.GraphSubgraphResponse;
import com.history.backend.graph.dto.SubgraphRequest;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GraphService {

    private final ProjectService projectService;
    private final AiEngineGraphClient aiEngineGraphClient;

    // 소유권 검증(인가 게이트)을 먼저 통과한 뒤에만 ai-engine 그래프를 조회한다.
    // ai-engine 호출은 외부 통신이라 트랜잭션 밖에서 수행 — getProject가 자체 read 트랜잭션을 갖는다.
    public GraphResponse getProjectGraph(UUID ownerId, UUID projectId, Integer limit, String types) {
        projectService.getProject(ownerId, projectId);
        return aiEngineGraphClient.fetchOverview(projectId, limit, types);
    }

    // 소유권 검증 후 그래프 노드 키워드 검색을 프록시한다 (통합 검색용). 빈 질의는 ai-engine 왕복 없이 빈 결과.
    public GraphSearchResponse searchNodes(UUID ownerId, UUID projectId, String q, Integer limit) {
        projectService.getProject(ownerId, projectId);
        if (q == null || q.isBlank()) {
            return GraphSearchResponse.empty();
        }
        return aiEngineGraphClient.searchNodes(projectId, q.trim(), limit);
    }

    // 소유권 검증 후 답변 evidence가 가리키는 관련 서브그래프를 ai-engine에서 조회한다 (대화 화면 그래프 패널용).
    public GraphSubgraphResponse getSubgraph(UUID ownerId, UUID projectId, SubgraphRequest request) {
        projectService.getProject(ownerId, projectId);
        return aiEngineGraphClient.fetchSubgraph(projectId, request.evidence());
    }

    // 소유권 검증 후 ai-engine 후처리 빌드를 프로젝트 단위로 트리거한다 — 소스 간 시맨틱 엣지 재구축.
    // verify=true면 방안 D(LLM 검증). projectId가 인가 게이트이자 실제 빌드 대상이며,
    // 빌드는 비동기(202)라 완료는 getBuildStatus 폴링으로 확인한다.
    public GraphBuildStatusResponse buildProjectGraph(UUID ownerId, UUID projectId, boolean verify) {
        projectService.getProject(ownerId, projectId);
        return aiEngineGraphClient.triggerBuild(projectId, verify);
    }

    // 소유권 검증 후 프로젝트의 빌드 상태를 조회한다 (트리거 후 폴링).
    public GraphBuildStatusResponse getBuildStatus(UUID ownerId, UUID projectId) {
        projectService.getProject(ownerId, projectId);
        return aiEngineGraphClient.fetchBuildStatus(projectId);
    }
}
