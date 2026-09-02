package com.history.backend.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.auth.service.UserService;
import com.history.backend.common.error.ForbiddenException;
import com.history.backend.common.error.NotFoundException;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.github.dto.GitHubInstallationAccountResponse;
import com.history.backend.github.dto.GitHubInstallationResponse;
import com.history.backend.github.dto.GitHubRepositoryOwnerResponse;
import com.history.backend.github.dto.GitHubRepositoryResponse;
import com.history.backend.github.repository.GitHubInstallationMemberRepository;
import com.history.backend.github.repository.GitHubInstallationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("GitHubInstallationService: GitHub App 설치 관리")
class GitHubInstallationServiceTest {

    @Mock
    private GitHubInstallationRepository gitHubInstallationRepository;

    @Mock
    private GitHubInstallationMemberRepository gitHubInstallationMemberRepository;

    @Mock
    private UserService userService;

    @Mock
    private GitHubUserTokenService gitHubUserTokenService;

    @Mock
    private GitHubOAuthClient gitHubOAuthClient;

    private static final UUID INSTALLER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");

    @Test
    @DisplayName("동시 삽입 충돌 시 재조회로 설치 정보 반환")
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
    @DisplayName("신규 설치 시 설치자를 멤버로 등록")
    void upsertInstallationRegistersInstallerAsMemberForNewInstallation() {
        GitHubInstallationService service = service();
        User installer = installer();
        UUID newInstallationId = UUID.fromString("6c1f5b2a-9e2a-4b8a-9a7c-2b7b6d4f9a10");
        GitHubInstallationResponse response = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("acme", "Organization")
        );
        GitHubInstallation createdInstallation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        ReflectionTestUtils.setField(createdInstallation, "id", newInstallationId);

        when(gitHubInstallationRepository.findByInstallationId(98765L)).thenReturn(Optional.empty());
        when(gitHubInstallationRepository.insertInstallationIfAbsent(98765L, "Organization", "acme", INSTALLER_ID))
                .thenReturn(Optional.of(newInstallationId));
        when(gitHubInstallationRepository.findById(newInstallationId)).thenReturn(Optional.of(createdInstallation));

        service.upsertInstallation(installer, response);

        verify(gitHubInstallationMemberRepository).addMember(newInstallationId, INSTALLER_ID);
    }

    @Test
    @DisplayName("기존 설치 재동의 시 동기화한 사용자를 멤버로 등록")
    void upsertInstallationRegistersSyncingUserAsMemberForExistingInstallation() {
        GitHubInstallationService service = service();
        User originalInstaller = installer();
        User syncingUser = new User("github", "99999", "teammate@example.com", "Teammate", null);
        UUID syncingUserId = UUID.fromString("7d2e6c3b-0f3b-4c9b-8b8d-3c8c7e5f0b21");
        ReflectionTestUtils.setField(syncingUser, "id", syncingUserId);
        GitHubInstallation existingInstallation =
                new GitHubInstallation(98765L, "Organization", "acme", originalInstaller);
        ReflectionTestUtils.setField(existingInstallation, "id", INSTALLATION_ID);
        GitHubInstallationResponse response = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("acme", "Organization")
        );

        when(gitHubInstallationRepository.findByInstallationId(98765L))
                .thenReturn(Optional.of(existingInstallation));

        service.upsertInstallation(syncingUser, response);

        verify(gitHubInstallationMemberRepository).addMember(INSTALLATION_ID, syncingUserId);
    }

    @Test
    @DisplayName("다른 사용자가 재동의해도 최초 설치자는 덮어써지지 않는다")
    void upsertInstallationDoesNotOverwriteOriginalInstaller() {
        GitHubInstallationService service = service();
        User originalInstaller = installer();
        User syncingUser = new User("github", "99999", "teammate@example.com", "Teammate", null);
        UUID syncingUserId = UUID.fromString("7d2e6c3b-0f3b-4c9b-8b8d-3c8c7e5f0b21");
        ReflectionTestUtils.setField(syncingUser, "id", syncingUserId);
        GitHubInstallation existingInstallation =
                new GitHubInstallation(98765L, "Organization", "acme", originalInstaller);
        ReflectionTestUtils.setField(existingInstallation, "id", INSTALLATION_ID);
        GitHubInstallationResponse response = new GitHubInstallationResponse(
                98765L,
                new GitHubInstallationAccountResponse("acme", "Organization")
        );

        when(gitHubInstallationRepository.findByInstallationId(98765L))
                .thenReturn(Optional.of(existingInstallation));

        GitHubInstallation result = service.upsertInstallation(syncingUser, response);

        // installer_user_id는 최초 설치자로 유지되고, 재동의한 사용자는 멤버로만 추가된다
        assertThat(result.getInstallerUser()).isSameAs(originalInstaller);
    }

    @Test
    @DisplayName("멤버인 사용자는 설치 정보를 얻는다")
    void getAccessibleInstallationReturnsInstallationForMember() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer());
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findByIdAndMemberUserId(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));

        GitHubInstallation result = service.getAccessibleInstallation(INSTALLER_ID, INSTALLATION_ID);

        assertThat(result).isSameAs(installation);
    }

    @Test
    @DisplayName("활성 사용자의 설치 목록을 멤버십 기준으로 조회")
    void findInstallationsReturnsInstallationsForMemberUser() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findAllByMemberUserId(INSTALLER_ID))
                .thenReturn(List.of(installation));

        var result = service.findInstallations(INSTALLER_ID);

        assertThat(result).hasSize(1);
        verify(userService).getActiveUser(INSTALLER_ID);
    }

    @Test
    @DisplayName("로그인 동기화 전용 멤버십 설치 목록은 활성 사용자 게이트 없이 저장소에 위임한다")
    void findMemberInstallationsDelegatesToRepositoryWithoutActiveUserGate() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        when(gitHubInstallationRepository.findAllByMemberUserId(INSTALLER_ID)).thenReturn(List.of(installation));

        var result = service.findMemberInstallations(INSTALLER_ID);

        assertThat(result).containsExactly(installation);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("탈퇴 사용자의 설치 목록 조회 거부")
    void findInstallationsRejectsDeletedInstaller() {
        GitHubInstallationService service = service();
        when(userService.getActiveUser(INSTALLER_ID))
                .thenThrow(new NotFoundException("User not found."));

        assertThatThrownBy(() -> service.findInstallations(INSTALLER_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found.");
        verify(gitHubInstallationRepository, never()).findAllByMemberUserId(any());
    }

    @Test
    @DisplayName("멤버가 아닌 사용자가 설치를 조회하면 NotFoundException")
    void getAccessibleInstallationRejectsNonMember() {
        GitHubInstallationService service = service();
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer());
        when(gitHubInstallationRepository.findByIdAndMemberUserId(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAccessibleInstallation(INSTALLER_ID, INSTALLATION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub installation not found.");
    }

    @Test
    @DisplayName("멤버인 사용자의 설치 저장소 목록을 user token ACL로 조회")
    void findRepositoriesReturnsRepositoriesForOwnedInstallation() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findByIdAndMemberUserId(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));
        when(gitHubUserTokenService.getAccessToken(INSTALLER_ID)).thenReturn("user-access-token");
        when(gitHubOAuthClient.fetchUserInstallationRepositories("user-access-token", 98765L))
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
        verify(gitHubUserTokenService).getAccessToken(INSTALLER_ID);
        verify(gitHubOAuthClient).fetchUserInstallationRepositories("user-access-token", 98765L);
        verify(gitHubOAuthClient, never()).fetchRepositoryBranches(any(), any(), any());
    }

    @Test
    @DisplayName("탈퇴 사용자의 리포지토리 조회 거부")
    void findRepositoriesRejectsDeletedInstaller() {
        GitHubInstallationService service = service();
        when(userService.getActiveUser(INSTALLER_ID))
                .thenThrow(new NotFoundException("User not found."));

        assertThatThrownBy(() -> service.findRepositories(INSTALLER_ID, INSTALLATION_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found.");
        verify(gitHubInstallationRepository, never()).findByIdAndMemberUserId(any(), any());
        verify(gitHubUserTokenService, never()).getAccessToken(any());
        verify(gitHubOAuthClient, never()).fetchUserInstallationRepositories(any(), any());
    }

    @Test
    @DisplayName("user token이 없으면 ForbiddenException을 전파하고 저장소 목록을 조회하지 않음")
    void findRepositoriesPropagatesReauthorizationRequired() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findByIdAndMemberUserId(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));
        when(gitHubUserTokenService.getAccessToken(INSTALLER_ID))
                .thenThrow(new ForbiddenException("GitHub reauthorization required."));

        assertThatThrownBy(() -> service.findRepositories(INSTALLER_ID, INSTALLATION_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("GitHub reauthorization required.");
        verify(gitHubOAuthClient, never()).fetchUserInstallationRepositories(any(), any());
    }

    @Test
    @DisplayName("멤버인 사용자의 브랜치 목록을 user token으로 조회")
    void findRepositoryBranchesReturnsBranchesForOwnedInstallation() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findByIdAndMemberUserId(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));
        when(gitHubUserTokenService.getAccessToken(INSTALLER_ID)).thenReturn("user-access-token");
        when(gitHubOAuthClient.fetchRepositoryBranches("user-access-token", "acme", "widget"))
                .thenReturn(List.of("main", "develop"));

        var result = service.findRepositoryBranches(INSTALLER_ID, INSTALLATION_ID, "acme", "widget");

        assertThat(result).containsExactly("main", "develop");
        verify(gitHubUserTokenService).getAccessToken(INSTALLER_ID);
        verify(gitHubOAuthClient).fetchRepositoryBranches("user-access-token", "acme", "widget");
        verify(gitHubOAuthClient, never()).fetchUserInstallationRepositories(any(), any());
    }

    @Test
    @DisplayName("브랜치 조회 중 NotFoundException을 그대로 전파")
    void findRepositoryBranchesPropagatesNotFound() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findByIdAndMemberUserId(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));
        when(gitHubUserTokenService.getAccessToken(INSTALLER_ID)).thenReturn("user-access-token");
        when(gitHubOAuthClient.fetchRepositoryBranches("user-access-token", "acme", "widget"))
                .thenThrow(new NotFoundException("GitHub repository not found."));

        assertThatThrownBy(() -> service.findRepositoryBranches(INSTALLER_ID, INSTALLATION_ID, "acme", "widget"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("GitHub repository not found.");
    }

    @Test
    @DisplayName("브랜치 조회 시 user token이 없으면 ForbiddenException을 전파하고 브랜치를 조회하지 않음")
    void findRepositoryBranchesPropagatesReauthorizationRequired() {
        GitHubInstallationService service = service();
        User installer = installer();
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", installer);
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        when(userService.getActiveUser(INSTALLER_ID)).thenReturn(installer);
        when(gitHubInstallationRepository.findByIdAndMemberUserId(INSTALLATION_ID, INSTALLER_ID))
                .thenReturn(Optional.of(installation));
        when(gitHubUserTokenService.getAccessToken(INSTALLER_ID))
                .thenThrow(new ForbiddenException("GitHub reauthorization required."));

        assertThatThrownBy(() -> service.findRepositoryBranches(INSTALLER_ID, INSTALLATION_ID, "acme", "widget"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("GitHub reauthorization required.");
        verify(gitHubOAuthClient, never()).fetchRepositoryBranches(any(), any(), any());
    }

    private GitHubInstallationService service() {
        return new GitHubInstallationService(
                gitHubInstallationRepository,
                gitHubInstallationMemberRepository,
                userService,
                gitHubUserTokenService,
                gitHubOAuthClient
        );
    }

    private User installer() {
        User installer = new User("github", "12345", "octocat@example.com", "Octocat", null);
        ReflectionTestUtils.setField(installer, "id", INSTALLER_ID);
        return installer;
    }
}
