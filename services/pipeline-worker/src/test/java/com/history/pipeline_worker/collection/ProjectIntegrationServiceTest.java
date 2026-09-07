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
    // PROJECT_ID보다 큰 값으로 두어 "project_id 순" 팬아웃 정렬을 명확히 검증한다.
    private static final UUID PROJECT_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final byte[] GITHUB_TOKEN = new byte[] {1};
    private static final byte[] JIRA_TOKEN = new byte[] {2};
    private static final byte[] SLACK_TOKEN = new byte[] {3};
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant FRESH_TOKEN_EXPIRY = Instant.parse("2026-01-01T01:00:00Z");
    private static final Instant STALE_TOKEN_EXPIRY = Instant.parse("2025-12-31T23:59:59Z");
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
        ProjectIntegrationRepository.IntegrationRow github = githubRow(FRESH_TOKEN_EXPIRY, GITHUB_TOKEN);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));
        when(repository.findAllByProjectId(PROJECT_ID))
                .thenReturn(List.of(github, jiraRow(), slackRow()));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn(JIRA_CREDENTIAL_JSON);
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("xoxb-slack-token");

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.READY);
        assertThat(result.contexts()).hasSize(1);
        ProjectCollectionContext context = result.contexts().get(0);
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
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of());

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.NOT_FOUND);
        verify(repository).findGitHubWebhookIntegrations(456L, 123L, "owner/repo");
    }

    @Test
    void resolveGitHubPullRequestWebhook_requiresRefreshWhenInstallationTokenIsMissing() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(FRESH_TOKEN_EXPIRY, null);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.TOKEN_REFRESH_REQUIRED);
    }

    @Test
    void resolveGitHubPullRequestWebhook_requiresRefreshWhenInstallationTokenIsExpired() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(STALE_TOKEN_EXPIRY, GITHUB_TOKEN);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.TOKEN_REFRESH_REQUIRED);
    }

    @Test
    void resolveGitHubPullRequestWebhook_requiresRefreshWithinFiveMinuteSkew() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(
                Instant.parse("2026-01-01T00:05:00Z"),
                GITHUB_TOKEN
        );
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.TOKEN_REFRESH_REQUIRED);
    }

    // 브랜치가 일치하지 않으면 토큰 신선도조차 확인하지 않고 BRANCH_MISMATCH로 끊는다 — DB 기록·토큰
    // 갱신·claim·큐잉 전부보다 앞이어야 한다는 설계 전제를 고정한다.
    @Test
    void resolveGitHubPullRequestWebhook_branchMismatch_returnsBranchMismatchWithoutBuildingContext() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(FRESH_TOKEN_EXPIRY, GITHUB_TOKEN);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload("develop"));

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.BRANCH_MISMATCH);
        assertThat(result.contexts()).isEmpty();
        verify(repository, never()).findAllByProjectId(any());
    }

    // 비교 순서 고정: 토큰이 만료됐어도 브랜치가 먼저 걸러지면 TOKEN_REFRESH_REQUIRED가 아니라
    // BRANCH_MISMATCH여야 한다. 순서를 뒤집은 구현(토큰 갱신 판정을 먼저 하는 구현)에서는 이 테스트가
    // TOKEN_REFRESH_REQUIRED를 돌려줘 실패한다.
    @Test
    void resolveGitHubPullRequestWebhook_branchMismatchWithExpiredToken_returnsBranchMismatchBeforeTokenRefresh() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(STALE_TOKEN_EXPIRY, GITHUB_TOKEN);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload("develop"));

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.BRANCH_MISMATCH);
    }

    @Test
    void resolveGitHubPullRequestWebhook_skipsInvalidOptionalIntegration() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(FRESH_TOKEN_EXPIRY, GITHUB_TOKEN);
        // cloud_id 누락 — 선택 연동의 설정 오류는 그 provider만 건너뛰고 나머지는 수집한다
        ProjectIntegrationRepository.IntegrationRow invalidJira = new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_ID,
                "jira",
                Map.of("project_key", "PLAT"),
                JIRA_TOKEN,
                null,
                null
        );
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));
        when(repository.findAllByProjectId(PROJECT_ID))
                .thenReturn(List.of(github, invalidJira, slackRow()));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");
        when(credentialCryptoService.decrypt(JIRA_TOKEN)).thenReturn(JIRA_CREDENTIAL_JSON);
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("Bearer xoxb-slack-token");

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.READY);
        assertThat(result.contexts()).hasSize(1);
        ProjectCollectionContext context = result.contexts().get(0);
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
                Map.of("repository_id", 123L, "branch", "main"),
                null,
                GITHUB_TOKEN,
                FRESH_TOKEN_EXPIRY
        );
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(invalidGitHub));
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(invalidGitHub));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");

        assertThatThrownBy(() -> service.resolveGitHubPullRequestWebhook(payload()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing external_ref value: repository_full_name");
    }

    @Test
    void resolveGitHubPullRequestWebhook_unresolvableGitHub_returnsNotFound() {
        // 만료된 토큰처럼 "지금은 수집 불가"인 경우는 예외가 아니라 not found로 떨어진다
        ProjectIntegrationRepository.IntegrationRow github = githubRow(FRESH_TOKEN_EXPIRY, GITHUB_TOKEN);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(slackRow()));
        when(credentialCryptoService.decrypt(SLACK_TOKEN)).thenReturn("xoxb-slack-token");

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload());

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.NOT_FOUND);
    }

    // 팬아웃: 같은 레포를 연결한 두 프로젝트 중 브랜치가 맞는 프로젝트만 골라야 한다. 첫 context만
    // 처리하거나 브랜치를 무시하고 전부 담는 구현에서는 contexts에 P1이 섞이거나 크기가 달라져 실패한다.
    @Test
    void resolveGitHubPullRequestWebhook_secondProjectBranchMatches_returnsOnlyMatchingProjectContext() {
        ProjectIntegrationRepository.IntegrationRow p1 = githubRow(PROJECT_ID, "main", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, true);
        ProjectIntegrationRepository.IntegrationRow p2 = githubRow(PROJECT_ID_2, "develop", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, true);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(p1, p2));
        when(repository.findAllByProjectId(PROJECT_ID_2)).thenReturn(List.of(p2));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload("develop"));

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.READY);
        assertThat(result.contexts()).hasSize(1);
        assertThat(result.contexts().get(0).projectId()).isEqualTo(PROJECT_ID_2.toString());
        verify(repository, never()).findAllByProjectId(PROJECT_ID);
    }

    // 팬아웃: 둘 다 브랜치가 맞으면 전부 큐잉 대상이 되고, project_id 오름차순으로 정렬돼 있어야 한다.
    // 첫 context만 처리(팬아웃 미구현)하는 실수는 contexts 크기가 1로 나와 이 테스트에서 잡힌다.
    @Test
    void resolveGitHubPullRequestWebhook_bothProjectsBranchMatch_returnsContextsOrderedByProjectId() {
        ProjectIntegrationRepository.IntegrationRow p1 = githubRow(PROJECT_ID, "main", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, true);
        ProjectIntegrationRepository.IntegrationRow p2 = githubRow(PROJECT_ID_2, "main", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, true);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(p1, p2));
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(p1));
        when(repository.findAllByProjectId(PROJECT_ID_2)).thenReturn(List.of(p2));
        when(credentialCryptoService.decrypt(GITHUB_TOKEN)).thenReturn("gh-token");

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload("main"));

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.READY);
        assertThat(result.contexts()).hasSize(2);
        assertThat(result.contexts().get(0).projectId()).isEqualTo(PROJECT_ID.toString());
        assertThat(result.contexts().get(1).projectId()).isEqualTo(PROJECT_ID_2.toString());
    }

    @Test
    void resolveGitHubPullRequestWebhook_bothProjectsIncrementalDisabled_returnsIncrementalDisabled() {
        ProjectIntegrationRepository.IntegrationRow p1 = githubRow(PROJECT_ID, "main", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, false);
        ProjectIntegrationRepository.IntegrationRow p2 = githubRow(PROJECT_ID_2, "develop", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, false);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(p1, p2));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload("main"));

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.INCREMENTAL_DISABLED);
        verify(repository, never()).findAllByProjectId(any());
    }

    // eligible이 비어도 원인이 "전부 disabled"가 아니면(하나는 그냥 브랜치가 다름) INCREMENTAL_DISABLED가
    // 아니라 BRANCH_MISMATCH로 떨어진다 — 두 원인을 뭉뚱그리면 사용자에게 잘못된 이유가 전달된다.
    @Test
    void resolveGitHubPullRequestWebhook_oneDisabledOneBranchMismatch_returnsBranchMismatch() {
        ProjectIntegrationRepository.IntegrationRow p1 = githubRow(PROJECT_ID, "main", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, false);
        ProjectIntegrationRepository.IntegrationRow p2 = githubRow(PROJECT_ID_2, "develop", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, true);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(p1, p2));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload("main"));

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.BRANCH_MISMATCH);
        verify(repository, never()).findAllByProjectId(any());
    }

    // installation token은 공유 자원이라 팬아웃 대상 중 하나만 만료돼도 전체를 TOKEN_REFRESH_REQUIRED로
    // 묶어야 한다 — 그래야 재해석 이후 모든 프로젝트가 같은 신선한 토큰으로 다시 평가된다.
    @Test
    void resolveGitHubPullRequestWebhook_onlyOneOfTwoProjectsHasExpiredToken_returnsTokenRefreshRequired() {
        ProjectIntegrationRepository.IntegrationRow p1 = githubRow(PROJECT_ID, "main", FRESH_TOKEN_EXPIRY, GITHUB_TOKEN, true);
        ProjectIntegrationRepository.IntegrationRow p2 = githubRow(PROJECT_ID_2, "main", STALE_TOKEN_EXPIRY, GITHUB_TOKEN, true);
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(p1, p2));

        GitHubWebhookIntegrationResolution result = service.resolveGitHubPullRequestWebhook(payload("main"));

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.TOKEN_REFRESH_REQUIRED);
        verify(repository, never()).findAllByProjectId(any());
    }

    @Test
    void resolveFetchRequest_buildsOnlyRequestedProvider() {
        ProjectIntegrationRepository.IntegrationRow github = githubRow(FRESH_TOKEN_EXPIRY, GITHUB_TOKEN);
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
                FRESH_TOKEN_EXPIRY
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
                FRESH_TOKEN_EXPIRY,
                false
        );
        when(repository.findGitHubWebhookIntegrations(456L, 123L, "owner/repo"))
                .thenReturn(List.of(github));

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
        return payload("main");
    }

    private GitHubWebhookPayload payload(String baseRef) {
        return new GitHubWebhookPayload("closed", true, "owner/repo", 123L, 456L, baseRef);
    }

    private ProjectIntegrationRepository.IntegrationRow githubRow(Instant expiresAt, byte[] encryptedToken) {
        return githubRow(PROJECT_ID, "main", expiresAt, encryptedToken, true);
    }

    // GitHubCollector.BRANCH(private)와 같은 "branch" 키를 쓴다 — 선택 브랜치.
    private ProjectIntegrationRepository.IntegrationRow githubRow(
            UUID projectId, String branch, Instant expiresAt, byte[] encryptedToken, boolean incrementalEnabled
    ) {
        return new ProjectIntegrationRepository.IntegrationRow(
                projectId,
                "github",
                Map.of("repository_id", 123L, "repository_full_name", "owner/repo", "branch", branch),
                null,
                encryptedToken,
                expiresAt,
                incrementalEnabled
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
