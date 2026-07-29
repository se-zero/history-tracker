package com.history.pipeline_worker.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.webhook.GitHubWebhookPayload;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectIntegrationServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final byte[] GITHUB_TOKEN = new byte[] {1};
    private static final byte[] JIRA_TOKEN = new byte[] {2};
    private static final byte[] SLACK_TOKEN = new byte[] {3};
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final String GATEWAY_BASE_URL = "https://api.atlassian.com/ex/jira";
    private static final String JIRA_CREDENTIAL_JSON =
            "{\"access_token\":\"jira-access-token\",\"refresh_token\":\"jira-refresh-token\","
                    + "\"expires_at\":\"2026-01-01T01:00:00Z\"}";

    private final ProjectIntegrationRepository repository = mock(ProjectIntegrationRepository.class);
    private final CredentialCryptoService credentialCryptoService = mock(CredentialCryptoService.class);
    private final ProjectIntegrationService service =
            new ProjectIntegrationService(repository, credentialCryptoService, new ObjectMapper(), GATEWAY_BASE_URL, CLOCK);

    @Test
    void resolveGitHubPullRequestWebhook_buildsCollectionContextFromProjectIntegrations() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2026-01-01T01:00:00Z"),
                GITHUB_TOKEN
        );
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.of(github));
        when(repository.findAllByProjectId(PROJECT_ID))
                .thenReturn(List.of(github, jiraRow(), slackRow()));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn(JIRA_CREDENTIAL_JSON);
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("xoxb-slack-token");

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.READY);
        ProjectCollectionContext context = result.context();
        assertThat(context.projectId()).isEqualTo(PROJECT_ID.toString());
        assertThat(context.github().credentials()).isEqualTo("Bearer gh-token");
        assertThat(context.github().repositoryFullName()).isEqualTo("owner/repo");
        assertThat(context.jira()).hasValueSatisfying(jira -> {
            assertThat(jira.credentials()).isEqualTo("Bearer jira-access-token");
            assertThat(jira.projectKey()).isEqualTo("PLAT");
            assertThat(jira.baseUrl()).isEqualTo(GATEWAY_BASE_URL + "/CLOUD123");
        });
        assertThat(context.slack()).hasValueSatisfying(slack ->
                assertThat(slack.credentials()).isEqualTo("Bearer xoxb-slack-token"));
    }

    @Test
    void resolveGitHubPullRequestWebhook_returnsNotFoundWhenNoGitHubIntegrationMatches() {
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.empty());

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.NOT_FOUND);
        verify(repository).findGitHubWebhookIntegration(456L, 123L, "owner/repo");
    }

    @Test
    void resolveGitHubPullRequestWebhook_requiresRefreshWhenInstallationTokenIsMissing() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2026-01-01T01:00:00Z"),
                null
        );
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.of(github));
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.TOKEN_REFRESH_REQUIRED);
    }

    @Test
    void resolveGitHubPullRequestWebhook_requiresRefreshWhenInstallationTokenIsExpired() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2025-12-31T23:59:59Z"),
                GITHUB_TOKEN
        );
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.of(github));
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.TOKEN_REFRESH_REQUIRED);
    }

    @Test
    void resolveGitHubPullRequestWebhook_requiresRefreshWithinFiveMinuteSkew() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2026-01-01T00:05:00Z"),
                GITHUB_TOKEN
        );
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.TOKEN_REFRESH_REQUIRED);
    }

    @Test
    void resolveGitHubPullRequestWebhook_skipsInvalidOptionalIntegration() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2026-01-01T01:00:00Z"),
                GITHUB_TOKEN
        );
        ProjectIntegrationRepository.IntegrationRow invalidJira = new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_ID,
                "jira",
                Map.of("project_key", "PLAT"),
                JIRA_TOKEN,
                null,
                null
        );
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.of(github));
        when(repository.findAllByProjectId(PROJECT_ID))
                .thenReturn(List.of(github, invalidJira, slackRow()));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn(JIRA_CREDENTIAL_JSON);
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("Bearer xoxb-slack-token");

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.READY);
        ProjectCollectionContext context = result.context();
        assertThat(context.github().credentials()).isEqualTo("Bearer gh-token");
        assertThat(context.jira()).isEmpty();
        assertThat(context.slack()).hasValueSatisfying(slack ->
                assertThat(slack.credentials()).isEqualTo("Bearer xoxb-slack-token"));
    }

    @Test
    void resolveGitHubPullRequestWebhook_invalidGitHubExternalRef_propagatesConfigurationError() {
        ProjectIntegrationRepository.IntegrationRow invalidGitHub = new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_ID,
                "github",
                Map.of("repository_id", 123L),
                null,
                GITHUB_TOKEN,
                Instant.parse("2026-01-01T01:00:00Z")
        );
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.of(invalidGitHub));
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(invalidGitHub));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");

        assertThatThrownBy(() -> service.resolveGitHubPullRequestWebhook(payload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing external_ref value: repository_full_name");
    }

    @Test
    void resolveGitHub_buildsOnlyGitHubIntegrationForProject() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2026-01-01T01:00:00Z"),
                GITHUB_TOKEN
        );
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(jiraRow(), github, slackRow()));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");

        Optional<GitHubIntegration> result = service.resolveGitHub(PROJECT_ID);

        assertThat(result).hasValueSatisfying(integration -> {
            assertThat(integration.credentials()).isEqualTo("Bearer gh-token");
            assertThat(integration.repositoryFullName()).isEqualTo("owner/repo");
        });
    }

    @Test
    void resolveJira_buildsOnlyJiraIntegrationForProject() {
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(slackRow(), jiraRow()));
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn(JIRA_CREDENTIAL_JSON);

        Optional<JiraIntegration> result = service.resolveJira(PROJECT_ID);

        assertThat(result).hasValueSatisfying(integration -> {
            assertThat(integration.credentials()).isEqualTo("Bearer jira-access-token");
            assertThat(integration.projectKey()).isEqualTo("PLAT");
            assertThat(integration.baseUrl()).isEqualTo(GATEWAY_BASE_URL + "/CLOUD123");
        });
    }

    @Test
    void resolveJira_returnsEmptyWhenCredentialJsonIsBroken() {
        // OAuth 전환 후 credential은 JSON이라, 깨진 JSON도 IllegalStateException으로 감싸 걸러야 한다.
        // 감싸지 않으면 buildOptionalIntegration의 안전망(IllegalArgumentException|IllegalStateException만
        // 잡음)을 우회해 webhook 수집 전체가 실패한다.
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(jiraRow()));
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn("not-valid-json");

        assertThat(service.resolveJira(PROJECT_ID)).isEmpty();
    }

    @Test
    void resolveJira_returnsEmptyWhenCredentialJsonIsMissingAccessToken() {
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(jiraRow()));
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn("{\"refresh_token\":\"jira-refresh-token\"}");

        assertThat(service.resolveJira(PROJECT_ID)).isEmpty();
    }

    @Test
    void requiredCredentialString_throwsMessageDistinctFromExternalRefFailures() {
        // access_token은 external_ref가 아니라 암호화된 credential JSON 안에 있으므로,
        // requiredString과 같은 "Missing external_ref value: ..." 메시지를 재사용하면 엉뚱한 곳을 가리켜
        // 디버깅을 오도한다.
        assertThatThrownBy(() -> service.requiredCredentialString(Map.of(), "access_token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing Jira credential field: access_token");
    }

    @Test
    void resolveSlack_buildsOnlySlackIntegrationForProject() {
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(slackRow()));
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("xoxb-slack-token");

        Optional<SlackIntegration> result = service.resolveSlack(PROJECT_ID);

        assertThat(result).hasValueSatisfying(integration ->
                assertThat(integration.credentials()).isEqualTo("Bearer xoxb-slack-token"));
    }

    @Test
    void resolveGitHub_returnsEmptyWhenIntegrationIsInvalid() {
        ProjectIntegrationRepository.IntegrationRow invalidGitHub = new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_ID,
                "github",
                Map.of(),
                null,
                GITHUB_TOKEN,
                Instant.parse("2026-01-01T01:00:00Z")
        );
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(invalidGitHub));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");

        assertThat(service.resolveGitHub(PROJECT_ID)).isEmpty();
    }

    private GitHubWebhookPayload payload() {
        return new GitHubWebhookPayload("closed", true, "owner/repo", 123L, 456L);
    }

    private ProjectIntegrationRepository.IntegrationRow githubRow(Instant expiresAt, byte[] encryptedToken) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_ID,
                "github",
                Map.of("repository_id", 123L, "repository_full_name", "owner/repo"),
                null,
                encryptedToken,
                expiresAt
        );
    }

    private ProjectIntegrationRepository.IntegrationRow jiraRow() {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_ID,
                "jira",
                Map.of("project_key", "PLAT", "cloud_id", "CLOUD123"),
                JIRA_TOKEN,
                null,
                null
        );
    }

    private ProjectIntegrationRepository.IntegrationRow slackRow() {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_ID,
                "slack",
                Map.of("workspace_id", "T123", "workspace_name", "Acme"),
                SLACK_TOKEN,
                null,
                null
        );
    }
}
