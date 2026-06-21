package com.history.backend.github.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.history.backend.github.service.InstallationTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

@DisplayName("InternalGitHubInstallationController: 내부 GitHub 설치 토큰 API")
class InternalGitHubInstallationControllerTest {

    @Test
    @DisplayName("installation 토큰 확보 요청 → 204 No Content 반환")
    void ensureInstallationTokenReturnsNoContent() {
        InstallationTokenService installationTokenService = mock(InstallationTokenService.class);
        InternalGitHubInstallationController controller =
                new InternalGitHubInstallationController(installationTokenService);

        ResponseEntity<Void> response = controller.ensureInstallationToken(456L);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(installationTokenService).ensureInstallationAccessToken(456L);
    }
}
