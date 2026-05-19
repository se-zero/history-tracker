package com.history.backend.github.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.history.backend.github.dto.InstallationResponse;
import com.history.backend.github.dto.RepositoryResponse;
import com.history.backend.github.service.GitHubInstallationService;
import com.history.backend.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitHubInstallationControllerTest {

    private static final UUID USER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID INSTALLATION_ROW_ID = UUID.fromString("3cb81470-5e1d-49db-84a7-0d0c11dc9509");

    @Mock
    private GitHubInstallationService gitHubInstallationService;

    @Test
    void listInstallationsReturnsCurrentUserInstallations() {
        List<InstallationResponse> response = List.of(new InstallationResponse(
                INSTALLATION_ROW_ID,
                98765L,
                "Organization",
                "acme"
        ));
        when(gitHubInstallationService.findInstallations(USER_ID)).thenReturn(response);

        List<InstallationResponse> result = new GitHubInstallationController(gitHubInstallationService)
                .listInstallations(new AuthenticatedUser(USER_ID));

        assertThat(result).isEqualTo(response);
        verify(gitHubInstallationService).findInstallations(USER_ID);
    }

    @Test
    void listRepositoriesReturnsInstallationRepositories() {
        List<RepositoryResponse> response = List.of(new RepositoryResponse(
                12345L,
                "widget",
                "acme/widget",
                "acme",
                true,
                "private",
                "main"
        ));
        when(gitHubInstallationService.findRepositories(USER_ID, INSTALLATION_ROW_ID)).thenReturn(response);

        List<RepositoryResponse> result = new GitHubInstallationController(gitHubInstallationService)
                .listRepositories(new AuthenticatedUser(USER_ID), INSTALLATION_ROW_ID);

        assertThat(result).isEqualTo(response);
        verify(gitHubInstallationService).findRepositories(USER_ID, INSTALLATION_ROW_ID);
    }
}
