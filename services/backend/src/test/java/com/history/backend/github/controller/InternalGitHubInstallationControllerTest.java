package com.history.backend.github.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.history.backend.github.service.InstallationTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class InternalGitHubInstallationControllerTest {

    @Test
    void ensureInstallationTokenReturnsNoContent() {
        InstallationTokenService installationTokenService = mock(InstallationTokenService.class);
        InternalGitHubInstallationController controller =
                new InternalGitHubInstallationController(installationTokenService);

        ResponseEntity<Void> response = controller.ensureInstallationToken(456L);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(installationTokenService).ensureInstallationAccessToken(456L);
    }
}
