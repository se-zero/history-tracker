package com.history.backend.github.dto;

import java.util.UUID;

import com.history.backend.github.domain.GitHubInstallation;

public record InstallationResponse(
        UUID id,
        Long installationId,
        String accountType,
        String accountLogin
) {

    public static InstallationResponse from(GitHubInstallation installation) {
        return new InstallationResponse(
                installation.getId(),
                installation.getInstallationId(),
                installation.getAccountType(),
                installation.getAccountLogin()
        );
    }
}
