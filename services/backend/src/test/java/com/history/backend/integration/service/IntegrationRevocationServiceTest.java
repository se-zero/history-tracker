package com.history.backend.integration.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.history.backend.auth.domain.User;
import com.history.backend.github.domain.GitHubInstallation;
import com.history.backend.integration.domain.Integration;
import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.integration.repository.IntegrationRepository;
import com.history.backend.project.domain.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// IntegrationService.disconnect의 private revokeProviderAccess를 leaf 서비스로 추출한 것 —
// ProjectService(프로젝트 삭제 시 전체 연동 일괄 폐기)와 IntegrationService(해제 시 단건 폐기)가
// 공유한다. ProjectService가 IntegrationService를 직접 부르면 순환 의존(IntegrationService →
// ProjectService)이 생기므로 둘 다 참조 가능한 leaf로 둔다.
@ExtendWith(MockitoExtension.class)
@DisplayName("IntegrationRevocationService: 프로젝트 연동 provider OAuth 권한 폐기")
class IntegrationRevocationServiceTest {

    private static final UUID OWNER_ID = UUID.fromString("fdd87bd0-3751-4336-a2db-c05d931c4f50");
    private static final UUID PROJECT_ID = UUID.fromString("f4dfc513-bb7b-41f4-aaf9-46bcc18380f8");
    private static final UUID INSTALLATION_ID = UUID.fromString("45b30a75-46d0-4402-b842-9e9c7d07e9ab");

    @Mock
    private IntegrationRepository integrationRepository;

    @Mock
    private ProviderCredentialLifecycleRegistry credentialLifecycles;

    @Test
    @DisplayName("연동 3건 각각의 encryptedCredential·externalRef로 provider별 lifecycle.revoke를 호출한다")
    void revokeAllRevokesEachIntegrationWithItsOwnCredentialAndExternalRef() {
        IntegrationRevocationService service = service();
        Project project = project();
        Integration slack = Integration.oauth(
                project, IntegrationProvider.SLACK, Map.of("workspace_id", "T1"), new byte[] {1, 2, 3});
        Integration jira = Integration.oauth(
                project, IntegrationProvider.JIRA, Map.of("cloud_id", "C1"), new byte[] {4, 5, 6});
        Integration discord = Integration.oauth(
                project, IntegrationProvider.DISCORD, Map.of("guild_id", "G1"), new byte[] {7, 8, 9});
        ProviderCredentialLifecycle slackLifecycle = mock(ProviderCredentialLifecycle.class);
        ProviderCredentialLifecycle jiraLifecycle = mock(ProviderCredentialLifecycle.class);
        ProviderCredentialLifecycle discordLifecycle = mock(ProviderCredentialLifecycle.class);
        when(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(slack, jira, discord));
        when(credentialLifecycles.find(IntegrationProvider.SLACK)).thenReturn(Optional.of(slackLifecycle));
        when(credentialLifecycles.find(IntegrationProvider.JIRA)).thenReturn(Optional.of(jiraLifecycle));
        when(credentialLifecycles.find(IntegrationProvider.DISCORD)).thenReturn(Optional.of(discordLifecycle));

        service.revokeAll(PROJECT_ID);

        verify(slackLifecycle).revoke(eq(slack.getEncryptedCredential()), eq(slack.getExternalRef()));
        verify(jiraLifecycle).revoke(eq(jira.getEncryptedCredential()), eq(jira.getExternalRef()));
        verify(discordLifecycle).revoke(eq(discord.getEncryptedCredential()), eq(discord.getExternalRef()));
    }

    @Test
    @DisplayName("한 provider의 revoke가 RuntimeException을 던져도 나머지 provider 폐기는 계속 진행되고 예외를 전파하지 않는다")
    void revokeAllContinuesRemainingProvidersWhenOneRevokeThrows() {
        IntegrationRevocationService service = service();
        Project project = project();
        Integration slack = Integration.oauth(
                project, IntegrationProvider.SLACK, Map.of("workspace_id", "T1"), new byte[] {1, 2, 3});
        Integration jira = Integration.oauth(
                project, IntegrationProvider.JIRA, Map.of("cloud_id", "C1"), new byte[] {4, 5, 6});
        Integration discord = Integration.oauth(
                project, IntegrationProvider.DISCORD, Map.of("guild_id", "G1"), new byte[] {7, 8, 9});
        ProviderCredentialLifecycle slackLifecycle = mock(ProviderCredentialLifecycle.class);
        ProviderCredentialLifecycle jiraLifecycle = mock(ProviderCredentialLifecycle.class);
        ProviderCredentialLifecycle discordLifecycle = mock(ProviderCredentialLifecycle.class);
        when(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(slack, jira, discord));
        when(credentialLifecycles.find(IntegrationProvider.SLACK)).thenReturn(Optional.of(slackLifecycle));
        when(credentialLifecycles.find(IntegrationProvider.JIRA)).thenReturn(Optional.of(jiraLifecycle));
        when(credentialLifecycles.find(IntegrationProvider.DISCORD)).thenReturn(Optional.of(discordLifecycle));
        doThrow(new RuntimeException("Slack revoke failed.")).when(slackLifecycle).revoke(any(), any());

        assertThatCode(() -> service.revokeAll(PROJECT_ID)).doesNotThrowAnyException();

        // 첫 provider(Slack)가 실패해도 이어지는 Jira·Discord 폐기가 진행돼야 한다 —
        // 구현이 첫 예외에서 순회를 중단하면 이 두 verify가 실패해 잡아낸다
        verify(jiraLifecycle).revoke(eq(jira.getEncryptedCredential()), eq(jira.getExternalRef()));
        verify(discordLifecycle).revoke(eq(discord.getEncryptedCredential()), eq(discord.getExternalRef()));
    }

    @Test
    @DisplayName("레지스트리에 등록되지 않은 provider(GitHub)는 건너뛰고 예외 없이 통과한다")
    void revokeAllSkipsProviderWithoutRegisteredLifecycle() {
        IntegrationRevocationService service = service();
        Project project = project();
        Integration github = Integration.github(project, installation(project), 12345L, "acme/widget", "main");
        when(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID))
                .thenReturn(List.of(github));
        when(credentialLifecycles.find(IntegrationProvider.GITHUB)).thenReturn(Optional.empty());

        assertThatCode(() -> service.revokeAll(PROJECT_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("연동이 없는 프로젝트는 아무 것도 호출하지 않는다")
    void revokeAllDoesNothingWhenProjectHasNoIntegrations() {
        IntegrationRevocationService service = service();
        when(integrationRepository.findAllByProject_IdOrderByCreatedAtDesc(PROJECT_ID)).thenReturn(List.of());

        service.revokeAll(PROJECT_ID);

        verifyNoInteractions(credentialLifecycles);
    }

    @Test
    @DisplayName("단건 폐기 — 등록된 provider의 lifecycle.revoke를 호출한다")
    void revokeCallsRegisteredLifecycleForSingleIntegration() {
        IntegrationRevocationService service = service();
        Integration slack = Integration.oauth(
                project(), IntegrationProvider.SLACK, Map.of("workspace_id", "T1"), new byte[] {1, 2, 3});
        ProviderCredentialLifecycle slackLifecycle = mock(ProviderCredentialLifecycle.class);
        when(credentialLifecycles.find(IntegrationProvider.SLACK)).thenReturn(Optional.of(slackLifecycle));

        service.revoke(slack);

        verify(slackLifecycle).revoke(eq(slack.getEncryptedCredential()), eq(slack.getExternalRef()));
    }

    @Test
    @DisplayName("단건 폐기 — 레지스트리에 없는 provider는 아무 것도 호출하지 않는다")
    void revokeIsNoopForProviderWithoutRegisteredLifecycle() {
        IntegrationRevocationService service = service();
        Project project = project();
        Integration github = Integration.github(project, installation(project), 12345L, "acme/widget", "main");
        when(credentialLifecycles.find(IntegrationProvider.GITHUB)).thenReturn(Optional.empty());

        assertThatCode(() -> service.revoke(github)).doesNotThrowAnyException();
    }

    private IntegrationRevocationService service() {
        return new IntegrationRevocationService(integrationRepository, credentialLifecycles);
    }

    private User user() {
        User user = new User("github", "12345", "owner@example.com", "Owner", null);
        ReflectionTestUtils.setField(user, "id", OWNER_ID);
        return user;
    }

    private Project project() {
        Project project = new Project(user(), "History Tracker", null);
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        return project;
    }

    private GitHubInstallation installation(Project project) {
        GitHubInstallation installation = new GitHubInstallation(98765L, "Organization", "acme", project.getOwner());
        ReflectionTestUtils.setField(installation, "id", INSTALLATION_ID);
        return installation;
    }
}
