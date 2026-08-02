package com.history.backend.graph.controller;

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
import com.history.backend.graph.service.ActorService;
import com.history.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/actors")
public class ActorController {

    private final ActorService actorService;

    @GetMapping
    public ActorListResponse getActors(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return actorService.getActors(authenticatedUser.id(), projectId);
    }

    @GetMapping("/{actorUuid}")
    public ActorDetailResponse getActorDetail(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable String actorUuid
    ) {
        return actorService.getActorDetail(authenticatedUser.id(), projectId, actorUuid);
    }

    @PostMapping("/merge")
    public ActorMergeResponse mergeActors(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestBody ActorMergeRequest request
    ) {
        return actorService.mergeActors(authenticatedUser.id(), projectId, request);
    }

    @PostMapping("/rename")
    public ActorRenameResponse renameActor(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestBody ActorRenameRequest request
    ) {
        return actorService.renameActor(authenticatedUser.id(), projectId, request);
    }

    @PostMapping("/unmerge")
    public ActorUnmergeResponse unmergeActors(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestBody ActorUnmergeRequest request
    ) {
        return actorService.unmergeActors(authenticatedUser.id(), projectId, request);
    }

    @PostMapping("/split")
    public ActorSplitResponse splitActor(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestBody ActorSplitRequest request
    ) {
        return actorService.splitActor(authenticatedUser.id(), projectId, request);
    }

    @GetMapping("/decisions")
    public ActorDecisionListResponse getDecisions(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return actorService.getDecisions(authenticatedUser.id(), projectId);
    }

    @DeleteMapping("/decisions/{decisionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeDecision(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable String decisionId
    ) {
        actorService.revokeDecision(authenticatedUser.id(), projectId, decisionId);
    }
}
