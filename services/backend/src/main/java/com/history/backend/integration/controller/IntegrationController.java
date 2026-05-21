package com.history.backend.integration.controller;

import java.util.UUID;

import com.history.backend.integration.dto.ConnectGitHubIntegrationRequest;
import com.history.backend.integration.dto.ConnectJiraIntegrationRequest;
import com.history.backend.integration.dto.ConnectSlackIntegrationRequest;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/integrations")
public class IntegrationController {

    private final IntegrationService integrationService;

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
                request.repositoryFullName()
        ));
    }

    @PostMapping("/slack")
    @ResponseStatus(HttpStatus.CREATED)
    public IntegrationResponse connectSlackWorkspace(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody ConnectSlackIntegrationRequest request
    ) {
        return IntegrationResponse.from(integrationService.connectSlackWorkspace(
                authenticatedUser.id(),
                projectId,
                request.token()
        ));
    }

    @PostMapping("/jira")
    @ResponseStatus(HttpStatus.CREATED)
    public IntegrationResponse connectJiraProject(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @Valid @RequestBody ConnectJiraIntegrationRequest request
    ) {
        return IntegrationResponse.from(integrationService.connectJiraProject(
                authenticatedUser.id(),
                projectId,
                request.baseUrl(),
                request.projectKey(),
                request.email(),
                request.apiToken()
        ));
    }
}
