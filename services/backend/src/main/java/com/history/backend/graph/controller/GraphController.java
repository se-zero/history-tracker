package com.history.backend.graph.controller;

import java.util.UUID;

import com.history.backend.graph.dto.GraphBuildStatusResponse;
import com.history.backend.graph.dto.GraphResponse;
import com.history.backend.graph.dto.GraphSubgraphResponse;
import com.history.backend.graph.dto.SubgraphRequest;
import com.history.backend.graph.service.GraphService;
import com.history.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/graph")
public class GraphController {

    private final GraphService graphService;

    @GetMapping
    public GraphResponse getProjectGraph(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String types
    ) {
        return graphService.getProjectGraph(authenticatedUser.id(), projectId, limit, types);
    }

    @PostMapping("/subgraph")
    public GraphSubgraphResponse getProjectSubgraph(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestBody SubgraphRequest request
    ) {
        return graphService.getSubgraph(authenticatedUser.id(), projectId, request);
    }

    @PostMapping("/build")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GraphBuildStatusResponse buildProjectGraph(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "false") boolean verify
    ) {
        return graphService.buildProjectGraph(authenticatedUser.id(), projectId, verify);
    }

    @GetMapping("/build/status")
    public GraphBuildStatusResponse getBuildStatus(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return graphService.getBuildStatus(authenticatedUser.id(), projectId);
    }
}
