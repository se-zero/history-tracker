package com.history.backend.integration.controller;

import java.util.UUID;

import com.history.backend.integration.dto.ConnectGitHubIntegrationRequest;
import com.history.backend.integration.dto.IntegrationResponse;
import com.history.backend.integration.service.IntegrationService;
import com.history.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @DeleteMapping("/{integrationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteIntegration(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable UUID projectId,
            @PathVariable UUID integrationId
    ) {
        integrationService.deleteIntegration(authenticatedUser.id(), projectId, integrationId);
    }
}
