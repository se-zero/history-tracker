package com.history.backend.project.controller;

import java.util.List;
import java.util.UUID;

import com.history.backend.integration.service.IntegrationService;
import com.history.backend.project.dto.CreateProjectRequest;
import com.history.backend.project.dto.ProjectResponse;
import com.history.backend.project.dto.ReorderProjectsRequest;
import com.history.backend.project.dto.UpdateProjectRequest;
import com.history.backend.project.service.ProjectService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;
    // 프로젝트 + GitHub 연동 동시 생성만 위임한다 — 연동 저장은 integration 쪽이 소유한다.
    private final IntegrationService integrationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse createProject(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody CreateProjectRequest request
    ) {
        if (request.github() != null) {
            return ProjectResponse.from(integrationService.createProjectWithGitHubRepository(
                    authenticatedUser.id(),
                    request.name(),
                    request.description(),
                    request.github().installationId(),
                    request.github().repositoryId(),
                    request.github().repositoryFullName(),
                    request.github().branch()
            ));
        }
        return ProjectResponse.from(projectService.createProject(
                authenticatedUser.id(),
                request.name(),
                request.description()
        ));
    }

    @GetMapping
    public List<ProjectResponse> listProjects(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return projectService.findProjects(authenticatedUser.id()).stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @PatchMapping("/order")
    public List<ProjectResponse> reorderProjects(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody ReorderProjectsRequest request
    ) {
        return projectService.reorderProjects(authenticatedUser.id(), request.orderedIds()).stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return ProjectResponse.from(projectService.getProject(authenticatedUser.id(), projectId));
    }

    @PutMapping("/{projectId}")
    public ProjectResponse updateProject(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest request
    ) {
        return ProjectResponse.from(projectService.updateProject(
                authenticatedUser.id(),
                projectId,
                request.name(),
                request.description()
        ));
    }

    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        projectService.deleteProject(authenticatedUser.id(), projectId);
    }
}
