package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.GitHubInstallationAccountResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.repository.GitHubInstallationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class GitHubInstallationServiceTest {

    @Mock
    private GitHubInstallationRepository gitHubInstallationRepository;

    private static final UUID INSTALLER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");

    @Test
    void upsertInstallationReloadsInstallationWhenConcurrentInsertWins() {
        GitHubInstallationService service = new GitHubInstallationService(gitHubInstallationRepository);
        User installer = new User("github", "12345", "octocat@example.com", "Octocat", null);
        ReflectionTestUtils.setField(installer, "id", INSTALLER_ID);
        GitHubInstallationResponse response = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("acme", "Organization")
        );
        GitHubInstallation savedByOtherRequest = new GitHubInstallation(98765L, "Organization", "acme", installer);

        when(gitHubInstallationRepository.findByInstallationId(98765L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(savedByOtherRequest));
        when(gitHubInstallationRepository.insertInstallationIfAbsent(any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        GitHubInstallation result = service.upsertInstallation(installer, response);

        assertThat(result).isSameAs(savedByOtherRequest);
    }

    @Test
    void getInstallationForInstallerReturnsOwnedInstallation() {
        GitHubInstallationService service = new GitHubInstallationService(gitHubInstallationRepository);
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer());
        when(gitHubInstallationRepository.findByIdAndInstallerUser_Id(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));

        GitHubInstallation result = service.getInstallationForInstaller(INSTALLER_ID, INSTALLATION_ID);

        assertThat(result).isSameAs(installation);
    }

    @Test
    void getInstallationForInstallerRejectsMissingInstallation() {
        GitHubInstallationService service = new GitHubInstallationService(gitHubInstallationRepository);
        when(gitHubInstallationRepository.findByIdAndInstallerUser_Id(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInstallationForInstaller(INSTALLER_ID, INSTALLATION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub installation not found.");
    }

    private User installer() {
        User installer = new User("github", "12345", "octocat@example.com", "Octocat", null);
        ReflectionTestUtils.setField(installer, "id", INSTALLER_ID);
        return installer;
    }
}
