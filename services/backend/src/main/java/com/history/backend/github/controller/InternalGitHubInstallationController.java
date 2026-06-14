package com.history.backend.github.controller;

import com.history.backend.github.service.InstallationTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class InternalGitHubInstallationController {

    private final InstallationTokenService installationTokenService;

    @PostMapping("/api/v1/internal/github/installations/{installationId}/token")
    public ResponseEntity<Void> ensureInstallationToken(@PathVariable Long installationId) {
        installationTokenService.ensureInstallationAccessToken(installationId);
        return ResponseEntity.noContent().build();
    }
}
