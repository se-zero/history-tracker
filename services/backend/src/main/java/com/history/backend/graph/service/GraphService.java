package com.history.backend.graph.service;

import java.util.UUID;

import com.history.backend.auth.service.PlanService;
import com.history.backend.graph.dto.GraphActivityResponse;
import com.history.backend.graph.dto.GraphBuildStatusResponse;
import com.history.backend.graph.dto.GraphWorkUnitsResponse;
import com.history.backend.graph.dto.GraphResponse;
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
    private final PlanService planService;

    // 소유권 검증(인가 게이트)을 먼저 통과한 뒤에만 ai-engine 그래프를 조회한다.
    // ai-engine 호출은 외부 통신이라 트랜잭션 밖에서 수행 — getProject가 자체 read 트랜잭션을 갖는다.
    public GraphResponse getProjectGraph(UUID ownerId, UUID projectId, Integer limit, String types) {
        projectService.getProject(ownerId, projectId);
        return aiEngineGraphClient.fetchOverview(projectId, limit, types);
    }

    // 소유권 검증 후 작업 단위 뷰용 조회를 프록시한다 (작업 단위 화면용).
    public GraphWorkUnitsResponse getWorkUnits(UUID ownerId, UUID projectId, Integer limit) {
        projectService.getProject(ownerId, projectId);
        return aiEngineGraphClient.fetchWorkUnits(projectId, limit);
    }

    // 소유권 검증 후 작업 단위 드릴인 조회를 프록시한다. 빈 nodeId는 ai-engine 왕복 없이 빈 결과.
    public GraphResponse getWorkUnitNeighbors(UUID ownerId, UUID projectId, String nodeId) {
        projectService.getProject(ownerId, projectId);
        if (nodeId == null || nodeId.isBlank()) {
            return GraphResponse.empty();
        }
        return aiEngineGraphClient.fetchWorkUnitNeighbors(projectId, nodeId.trim());
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
        // 정밀 재구축(verify=true)만 무료 티어 한도를 검사한다 — 일반 재구축은 무제한
        if (verify) {
            planService.ensurePreciseRebuildAllowed(ownerId);
        }
        return aiEngineGraphClient.triggerBuild(projectId, verify);
    }

    // 소유권 검증 후 프로젝트의 빌드 상태를 조회한다 (트리거 후 폴링).
    public GraphBuildStatusResponse getBuildStatus(UUID ownerId, UUID projectId) {
        projectService.getProject(ownerId, projectId);
        return aiEngineGraphClient.fetchBuildStatus(projectId);
    }

    // 소유권 검증 후 프로젝트의 그래프 활동 상태를 조회한다 (프론트 채팅 게이팅용).
    public GraphActivityResponse getGraphActivity(UUID ownerId, UUID projectId) {
        projectService.getProject(ownerId, projectId);
        return aiEngineGraphClient.fetchGraphActivity(projectId);
    }
}
