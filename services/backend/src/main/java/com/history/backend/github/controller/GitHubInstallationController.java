package com.history.backend.github.controller;

import java.util.List;

import com.history.backend.github.dto.InstallationResponse;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/github/installations")
public class GitHubInstallationController {

    private final GitHubInstallationService gitHubInstallationService;

    @GetMapping
    public List<InstallationResponse> listInstallations(@AuthenticationPrincipal AuthenticatedUser authenticatedUser) {
        return gitHubInstallationService.findInstallations(authenticatedUser.id());
    }
}
