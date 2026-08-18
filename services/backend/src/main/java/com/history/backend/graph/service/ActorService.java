package com.history.backend.graph.service;

import java.util.UUID;

import com.history.backend.graph.dto.ActorDecisionListResponse;
import com.history.backend.graph.dto.ActorDetailResponse;
import com.history.backend.graph.dto.ActorListResponse;
import com.history.backend.graph.dto.ActorMergeRequest;
import com.history.backend.graph.dto.ActorMergeResponse;
import com.history.backend.graph.dto.ActorRenameRequest;
import com.history.backend.graph.dto.ActorRenameResponse;
import com.history.backend.graph.dto.ActorSplitRequest;
import com.history.backend.graph.dto.ActorSplitResponse;
import com.history.backend.graph.dto.ActorUnmergeRequest;
import com.history.backend.graph.dto.ActorUnmergeResponse;
import com.history.backend.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Actor 수동 병합·분리 — 소유권 검증(인가 게이트) 통과 후에만 ai-engine에 프록시한다.
@Service
@RequiredArgsConstructor
public class ActorService {

    private final ProjectService projectService;
    private final AiEngineActorClient aiEngineActorClient;

    public ActorListResponse getActors(UUID ownerId, UUID projectId) {
        projectService.getProject(ownerId, projectId);
        return aiEngineActorClient.fetchActors(projectId);
    }

    public ActorMergeResponse mergeActors(UUID ownerId, UUID projectId, ActorMergeRequest request) {
        projectService.getProject(ownerId, projectId);
        return aiEngineActorClient.mergeActors(projectId, request.uuidA(), request.uuidB(), request.note());
    }

    public ActorDetailResponse getActorDetail(UUID ownerId, UUID projectId, String actorUuid) {
        projectService.getProject(ownerId, projectId);
        return aiEngineActorClient.fetchActorDetail(projectId, actorUuid);
    }

    public ActorRenameResponse renameActor(UUID ownerId, UUID projectId, ActorRenameRequest request) {
        projectService.getProject(ownerId, projectId);
        return aiEngineActorClient.renameActor(projectId, request.actorUuid(), request.name());
    }

    public ActorUnmergeResponse unmergeActors(UUID ownerId, UUID projectId, ActorUnmergeRequest request) {
        projectService.getProject(ownerId, projectId);
        return aiEngineActorClient.unmergeActors(projectId, request.decisionId());
    }

    public ActorSplitResponse splitActor(UUID ownerId, UUID projectId, ActorSplitRequest request) {
        projectService.getProject(ownerId, projectId);
        return aiEngineActorClient.splitActor(projectId, request.actorUuid(), request.sourceIds());
    }

    public ActorDecisionListResponse getDecisions(UUID ownerId, UUID projectId) {
        projectService.getProject(ownerId, projectId);
        return aiEngineActorClient.fetchDecisions(projectId);
    }

    public void revokeDecision(UUID ownerId, UUID projectId, String decisionId) {
        projectService.getProject(ownerId, projectId);
        aiEngineActorClient.deleteDecision(projectId, decisionId);
    }
}
