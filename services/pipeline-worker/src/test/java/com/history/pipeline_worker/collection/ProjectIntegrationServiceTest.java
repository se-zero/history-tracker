package com.history.pipeline_worker.collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import com.history.pipeline_worker.source.github.GitHubCollector;
import com.history.pipeline_worker.source.github.GitHubNormalizer;
import com.history.pipeline_worker.source.github.GitHubRawService;
import com.history.pipeline_worker.source.jira.JiraCollector;
import com.history.pipeline_worker.source.jira.JiraNormalizer;
import com.history.pipeline_worker.source.jira.JiraRawService;
import com.history.pipeline_worker.source.slack.SlackCollector;
import com.history.pipeline_worker.source.slack.SlackNormalizer;
import com.history.pipeline_worker.source.slack.SlackRawService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 해석 정책 테스트 — 실제 collector를 물려 "어떤 실패를 삼키고 어떤 실패를 전파하는지"를 검증한다.
 * provider별 자격증명 해석 자체의 세부는 각 CollectorTest가 담당한다.
 */
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
    private final ProjectIntegrationService service = new ProjectIntegrationService(
            repository,
            new SourceCollectorRegistry(List.of(gitHubCollector(), jiraCollector(), slackCollector())),
            CLOCK
    );

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
        assertThat(context.request(CollectionProvider.GITHUB)).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer gh-token");
            assertThat(request.projectKey()).isEqualTo("owner/repo");
        });
        assertThat(context.request(CollectionProvider.JIRA)).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer jira-access-token");
            assertThat(request.projectKey()).isEqualTo("PLAT");
            assertThat(request.options()).containsEntry("baseUrl", GATEWAY_BASE_URL + "/CLOUD123");
        });
        assertThat(context.request(CollectionProvider.SLACK)).hasValueSatisfying(request ->
                assertThat(request.credentials()).isEqualTo("Bearer xoxb-slack-token"));
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
        // cloud_id 누락 — 선택 연동의 설정 오류는 그 provider만 건너뛰고 나머지는 수집한다
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
        assertThat(context.request(CollectionProvider.GITHUB)).hasValueSatisfying(request ->
                assertThat(request.credentials()).isEqualTo("Bearer gh-token"));
        assertThat(context.request(CollectionProvider.JIRA)).isEmpty();
        assertThat(context.request(CollectionProvider.SLACK)).hasValueSatisfying(request ->
                assertThat(request.credentials()).isEqualTo("Bearer xoxb-slack-token"));
    }

    @Test
    void resolveGitHubPullRequestWebhook_invalidGitHubExternalRef_propagatesConfigurationError() {
        // GitHub은 webhook 앵커라 설정 오류를 삼키면 "연동 없음"으로 오인돼 수집이 조용히 멈춘다
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
    void resolveGitHubPullRequestWebhook_unresolvableGitHub_returnsNotFound() {
        // 만료된 토큰처럼 "지금은 수집 불가"인 경우는 예외가 아니라 not found로 떨어진다
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2026-01-01T01:00:00Z"), GITHUB_TOKEN);
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.of(github));
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(slackRow()));
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("xoxb-slack-token");

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.NOT_FOUND);
    }

    @Test
    void resolveFetchRequest_buildsOnlyRequestedProvider() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2026-01-01T01:00:00Z"),
                GITHUB_TOKEN
        );
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(jiraRow(), github, slackRow()));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");

        Optional<RawFetchRequest> result = service.resolveFetchRequest(PROJECT_ID, CollectionProvider.GITHUB);

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer gh-token");
            assertThat(request.projectKey()).isEqualTo("owner/repo");
        });
    }

    @Test
    void resolveFetchRequest_jira_buildsGatewayBaseUrlFromCloudId() {
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(slackRow(), jiraRow()));
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn(JIRA_CREDENTIAL_JSON);

        Optional<RawFetchRequest> result = service.resolveFetchRequest(PROJECT_ID, CollectionProvider.JIRA);

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer jira-access-token");
            assertThat(request.projectKey()).isEqualTo("PLAT");
            assertThat(request.options()).containsEntry("baseUrl", GATEWAY_BASE_URL + "/CLOUD123");
        });
    }

    @Test
    void resolveFetchRequest_slack_wrapsTokenAsBearer() {
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(slackRow()));
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("xoxb-slack-token");

        assertThat(service.resolveFetchRequest(PROJECT_ID, CollectionProvider.SLACK))
                .hasValueSatisfying(request ->
                        assertThat(request.credentials()).isEqualTo("Bearer xoxb-slack-token"));
    }

    @Test
    void resolveFetchRequest_missingProviderIntegration_isEmpty() {
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(slackRow()));

        assertThat(service.resolveFetchRequest(PROJECT_ID, CollectionProvider.JIRA)).isEmpty();
    }

    @Test
    void resolveFetchRequest_brokenCredentialJson_isEmpty() {
        // 트리거 경로에서는 설정 오류도 삼킨다 — 한 provider의 오류가 트리거를 500으로 만들지 않게 한다
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(jiraRow()));
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn("not-valid-json");

        assertThat(service.resolveFetchRequest(PROJECT_ID, CollectionProvider.JIRA)).isEmpty();
    }

    @Test
    void resolveFetchRequest_credentialJsonMissingAccessToken_isEmpty() {
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(jiraRow()));
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn("{\"refresh_token\":\"jira-refresh-token\"}");

        assertThat(service.resolveFetchRequest(PROJECT_ID, CollectionProvider.JIRA)).isEmpty();
    }

    @Test
    void resolveFetchRequest_invalidGitHubExternalRef_isEmpty() {
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

        assertThat(service.resolveFetchRequest(PROJECT_ID, CollectionProvider.GITHUB)).isEmpty();
    }

    @Test
    void resolveGitHubPullRequestWebhook_returnsIncrementalDisabledWhenGitHubIntegrationHasIncrementalDisabled() {
        ProjectIntegrationRepository.IntegrationRow github = new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_ID,
                "github",
                Map.of("repository_id", 123L, "repository_full_name", "owner/repo"),
                null,
                GITHUB_TOKEN,
                Instant.parse("2026-01-01T01:00:00Z"),
                false
        );
        when(repository.findGitHubWebhookIntegration(456L, 123L, "owner/repo"))
                .thenReturn(Optional.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.INCREMENTAL_DISABLED);
        verify(repository, never()).findAllByProjectId(any());
    }

    private GitHubCollector gitHubCollector() {
        return new GitHubCollector(
                mock(GitHubRawService.class),
                mock(GitHubNormalizer.class),
                mock(EventPublisher.class),
                mock(CheckpointService.class),
                credentialCryptoService,
                CLOCK
        );
    }

    private JiraCollector jiraCollector() {
        return new JiraCollector(
                mock(JiraRawService.class),
                mock(JiraNormalizer.class),
                mock(EventPublisher.class),
                mock(CheckpointService.class),
                credentialCryptoService,
                new ObjectMapper(),
                GATEWAY_BASE_URL
        );
    }

    private SlackCollector slackCollector() {
        return new SlackCollector(
                mock(SlackRawService.class),
                mock(SlackNormalizer.class),
                mock(EventPublisher.class),
                mock(CheckpointService.class),
                credentialCryptoService
        );
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
