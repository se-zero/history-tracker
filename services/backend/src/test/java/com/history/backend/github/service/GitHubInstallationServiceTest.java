package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.GitHubInstallationAccountResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.dto.GitHubRepositoryOwnerResponse;
import com.history.backend.github.dto.GitHubRepositoryResponse;
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

    @Mock
    private GitHubAppClient gitHubAppClient;

    @Mock
    private InstallationTokenService installationTokenService;

    @Mock
    private UserService userService;

    private static final UUID INSTALLER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");

    @Test
    void upsertInstallationReloadsInstallationWhenConcurrentInsertWins() {
        GitHubInstallationService service = service();
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
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer());
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findByIdAndInstallerUser_Id(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));

        GitHubInstallation result = service.getInstallationForInstaller(INSTALLER_ID, INSTALLATION_ID);

        assertThat(result).isSameAs(installation);
    }

    @Test
    void findInstallationsRequiresActiveInstaller() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findAllByInstallerUser_Id(INSTALLER_ID))
                .thenReturn(List.of(installation));

        var result = service.findInstallations(INSTALLER_ID);

        assertThat(result).hasSize(1);
        verify(userService).getActiveUser(INSTALLER_ID);
    }

    @Test
    void findInstallationsRejectsDeletedInstaller() {
        GitHubInstallationService service = service();
        when(userService.getActiveUser(INSTALLER_ID))
                .thenThrow(new NotFoundException("User not found."));

        assertThatThrownBy(() -> service.findInstallations(INSTALLER_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found.");
        verify(gitHubInstallationRepository, never()).findAllByInstallerUser_Id(any());
    }

    @Test
    void getInstallationForInstallerRejectsMissingInstallation() {
        GitHubInstallationService service = service();
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer());
        when(gitHubInstallationRepository.findByIdAndInstallerUser_Id(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInstallationForInstaller(INSTALLER_ID, INSTALLATION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub installation not found.");
    }

    @Test
    void findRepositoriesReturnsRepositoriesForOwnedInstallation() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findByIdAndInstallerUser_Id(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));
        when(installationTokenService.getInstallationAccessToken(INSTALLATION_ID))
                .thenReturn("installation-token");
        when(gitHubAppClient.fetchInstallationRepositories("installation-token"))
                .thenReturn(List.of(new GitHubRepositoryResponse(
                        12345L,
                        "widget",
                        "acme/widget",
                        new GitHubRepositoryOwnerResponse("acme"),
                        true,
                        "private",
                        "main"
                )));

        var result = service.findRepositories(INSTALLER_ID, INSTALLATION_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(12345L);
        assertThat(result.get(0).fullName()).isEqualTo("acme/widget");
        assertThat(result.get(0).privateRepository()).isTrue();
        verify(gitHubAppClient, never()).createInstallationAccessToken(any());
    }

    @Test
    void findRepositoriesRejectsDeletedInstaller() {
        GitHubInstallationService service = service();
        when(userService.getActiveUser(INSTALLER_ID))
                .thenThrow(new NotFoundException("User not found."));

        assertThatThrownBy(() -> service.findRepositories(INSTALLER_ID, INSTALLATION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found.");
        verify(gitHubInstallationRepository, never()).findByIdAndInstallerUser_Id(any(), any());
        verify(installationTokenService, never()).getInstallationAccessToken(any());
        verify(gitHubAppClient, never()).fetchInstallationRepositories(any());
    }

    private GitHubInstallationService service() {
        return new GitHubInstallationService(
                gitHubInstallationRepository,
                gitHubAppClient,
                installationTokenService,
                userService
        );
    }

    private User installer() {
        User installer = new User("github", "12345", "octocat@example.com", "Octocat", null);
        ReflectionTestUtils.setField(installer, "id", INSTALLER_ID);
        return installer;
    }
}
