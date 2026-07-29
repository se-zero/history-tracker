package com.history.backend.integration.controller;

import java.util.List;
import java.util.UUID;

import com.history.backend.integration.dto.CompleteJiraProjectRequest;
import com.history.backend.integration.dto.ConnectGitHubIntegrationRequest;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.dto.JiraProjectResponse;
import com.history.backend.integration.dto.JiraSiteResponse;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/projects/{projectId}/integrations")
public class IntegrationController {

    private final IntegrationService integrationService;

    @GetMapping
    public List<IntegrationResponse> listIntegrations(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return integrationService.listIntegrations(authenticatedUser.id(), projectId);
    }

    @PostMapping("/github")
    @ResponseStatus(HttpStatus.CREATED)
    public IntegrationResponse connectGitHubRepository(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody ConnectGitHubIntegrationRequest request
    ) {
        return IntegrationResponse.from(integrationService.connectGitHubRepository(
                authenticatedUser.id(),
                projectId,
                request.installationId(),
                request.repositoryId(),
                request.repositoryFullName(),
                request.branch()
        ));
    }

    @GetMapping("/jira/sites")
    public List<JiraSiteResponse> listJiraSites(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId
    ) {
        return integrationService.listJiraSites(authenticatedUser.id(), projectId).stream()
                .map(JiraSiteResponse::from)
                .toList();
    }

    @GetMapping("/jira/projects")
    public List<JiraProjectResponse> listJiraProjects(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @RequestParam String cloudId
    ) {
        return integrationService.listJiraProjects(authenticatedUser.id(), projectId, cloudId).stream()
                .map(JiraProjectResponse::from)
                .toList();
    }

    @PostMapping("/jira/project")
    public IntegrationResponse completeJiraProject(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody CompleteJiraProjectRequest request
    ) {
        return IntegrationResponse.from(integrationService.completeJiraProject(
                authenticatedUser.id(),
                projectId,
                request.cloudId(),
                request.siteName(),
                request.projectKey(),
                request.projectName()
        ));
    }
}
