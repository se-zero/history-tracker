package com.history.backend.graph.service;

import java.util.UUID;

import com.history.backend.graph.dto.GraphResponse;
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
}
