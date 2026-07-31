package com.history.backend.integration.controller;

import java.util.UUID;

import com.history.backend.integration.service.JiraTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InternalJiraTokenController {

    private final JiraTokenService jiraTokenService;

    @PostMapping("/api/v1/internal/integrations/{projectId}/jira/token")
    public ResponseEntity<Void> ensureJiraToken(@PathVariable UUID projectId) {
        jiraTokenService.ensureAccessToken(projectId);
        return ResponseEntity.noContent().build();
    }
}
