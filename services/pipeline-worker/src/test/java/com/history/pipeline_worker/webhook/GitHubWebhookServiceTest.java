package com.history.pipeline_worker.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.GitHubWebhookIntegrationResolution;
import com.history.pipeline_worker.collection.IntegrationTokenClient;
import com.history.pipeline_worker.collection.ProjectCollectionContext;
import com.history.pipeline_worker.collection.ProjectIntegrationService;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.pipeline.CollectionResult;
import com.history.pipeline_worker.pipeline.PipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class GitHubWebhookServiceTest {

    private GitHubWebhookVerifier verifier;
    private WebhookDeliveryService webhookDeliveryService;
    private GitHubInstallationTokenClient installationTokenClient;
    private IntegrationTokenClient integrationTokenClient;
    private ProjectIntegrationService projectIntegrationService;
    private PipelineService pipelineService;
    private GitHubWebhookService service;

    @BeforeEach
    void setUp() {
        verifier = mock(GitHubWebhookVerifier.class);
        webhookDeliveryService = mock(WebhookDeliveryService.class);
        installationTokenClient = mock(GitHubInstallationTokenClient.class);
        integrationTokenClient = mock(IntegrationTokenClient.class);
        // 대부분의 테스트는 갱신 자체를 검증 대상으로 삼지 않는다 — 기본값을 NOT_SUPPORTED로 두어
        // (Slack·Discord처럼 갱신 수단이 없는 provider) context가 손대지 않은 채 그대로 흘러가게 한다.
        // 갱신을 검증하는 테스트는 필요한 provider만 개별 stub으로 덮어쓴다.
        when(integrationTokenClient.ensure(any(), any())).thenReturn(IntegrationTokenClient.TokenStatus.NOT_SUPPORTED);
        projectIntegrationService = mock(ProjectIntegrationService.class);
        pipelineService = mock(PipelineService.class);
        service = new GitHubWebhookService(
                new ObjectMapper(),
                verifier,
                webhookDeliveryService,
                installationTokenClient,
                integrationTokenClient,
                projectIntegrationService,
                pipelineService,
                new SyncTaskExecutor(),
                new ProjectCollectionSerializer(8)
        );
    }

    @Test
    void handle_invalidSignature_returnsUnauthorized() {
        HttpHeaders headers = headers();
        when(verifier.verify(payload(true, "closed"), "sig")).thenReturn(false);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload(true, "closed"));

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.UNAUTHORIZED);
        verifyNoInteractions(webhookDeliveryService, installationTokenClient, projectIntegrationService, pipelineService);
    }

    @Test
    void handle_nonPullRequestEvent_isIgnored() {
        HttpHeaders headers = headers();
        headers.set("X-GitHub-Event", "push");
        String payload = payload(true, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.IGNORED);
        verifyNoInteractions(webhookDeliveryService, installationTokenClient, projectIntegrationService, pipelineService);
    }

    @Test
    void handle_closedUnmergedPullRequest_isIgnored() {
        HttpHeaders headers = headers();
        String payload = payload(false, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.IGNORED);
        verify(webhookDeliveryService, never()).tryClaim(anyString(), anyString());
        verifyNoInteractions(installationTokenClient, projectIntegrationService, pipelineService);
    }

    @Test
    void handle_missingInstallation_returnsNotFoundWhenTokenRefreshIsRequired() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.tokenRefreshRequired());
        when(installationTokenClient.ensureInstallationToken(456L)).thenReturn(false);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.NOT_FOUND);
        verify(projectIntegrationService).resolveGitHubPullRequestWebhook(any());
        verifyNoInteractions(webhookDeliveryService, pipelineService);
    }

    @Test
    void handle_missingInstallationId_returnsBadRequest() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed").replace("\"installation\": { \"id\": 456 }", "\"installation\": {}");
        when(verifier.verify(payload, "sig")).thenReturn(true);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.BAD_REQUEST);
        verifyNoInteractions(installationTokenClient, projectIntegrationService, webhookDeliveryService, pipelineService);
    }

    @Test
    void handle_noProjectIntegration_returnsNotFound() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.notFound());

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.NOT_FOUND);
        verifyNoInteractions(installationTokenClient);
        verify(webhookDeliveryService, never()).tryClaim(anyString(), anyString());
        verifyNoInteractions(pipelineService);
    }

    @Test
    void handle_tokenRefreshRequired_refreshesAndResolvesIntegrationAgain() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext context = collectionContext();
        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(
                        GitHubWebhookIntegrationResolution.tokenRefreshRequired(),
                        GitHubWebhookIntegrationResolution.ready(context)
                );
        when(installationTokenClient.ensureInstallationToken(456L)).thenReturn(true);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(context)).thenReturn(collectionResult(1, 0, 0));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(installationTokenClient).ensureInstallationToken(456L);
        verify(projectIntegrationService, times(2)).resolveGitHubPullRequestWebhook(any());
        verify(pipelineService).collectIncremental(context);
    }

    @Test
    void handle_duplicateDelivery_doesNotRunPipeline() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(collectionContext()));
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(false);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.DUPLICATE);
        verifyNoInteractions(pipelineService);
    }

    @Test
    void handle_mergedPullRequest_runsIncrementalCollection() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext context = collectionContext();

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(context));
        when(pipelineService.collectIncremental(context)).thenReturn(collectionResult(1, 2, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(pipelineService).collectIncremental(context);
        verifyNoInteractions(installationTokenClient);
        verify(webhookDeliveryService).markProcessed("delivery-1");
    }

    @Test
    void handle_readyWithJiraIntegration_ensuresJiraTokenAndReResolvesJiraContext() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext contextWithStaleJira = collectionContextWithJira(jiraRequest("Bearer stale-jira-token"));
        Optional<RawFetchRequest> refreshedJira = Optional.of(jiraRequest("Bearer fresh-jira-token"));
        ProjectCollectionContext expectedContext =
                contextWithStaleJira.with(CollectionProvider.JIRA, refreshedJira.orElseThrow());

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(contextWithStaleJira));
        when(integrationTokenClient.ensure(UUID.fromString(projectId()), CollectionProvider.JIRA))
                .thenReturn(IntegrationTokenClient.TokenStatus.REFRESHED);
        when(projectIntegrationService.resolveFetchRequest(UUID.fromString(projectId()), CollectionProvider.JIRA))
                .thenReturn(refreshedJira);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(expectedContext)).thenReturn(collectionResult(1, 2, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(integrationTokenClient).ensure(UUID.fromString(projectId()), CollectionProvider.JIRA);
        verify(pipelineService).collectIncremental(expectedContext);
    }

    @Test
    void handle_jiraTokenEnsureFails_skipsJiraButContinuesGitHubCollection() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext contextWithJira = collectionContextWithJira(jiraRequest("Bearer stale-jira-token"));
        ProjectCollectionContext contextWithoutJira = contextWithJira.without(CollectionProvider.JIRA);

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(contextWithJira));
        when(integrationTokenClient.ensure(UUID.fromString(projectId()), CollectionProvider.JIRA))
                .thenReturn(IntegrationTokenClient.TokenStatus.FAILED);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(contextWithoutJira)).thenReturn(collectionResult(1, 0, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(pipelineService).collectIncremental(contextWithoutJira);
        verify(projectIntegrationService, never()).resolveFetchRequest(any(), any());
    }

    // IntegrationTokenClient는 예외를 던지지 않고 실패를 FAILED로 흡수한다(계약·검증은
    // IntegrationTokenClientTest 몫). 이 테스트는 그 계약이 아니라 "만료 토큰형이 아닌 provider도
    // 같은 반복문을 지난다"는 일반화 자체를 고정한다 — Jira가 유일하던 시절엔 없던 경로다.
    @Test
    void handle_slackTokenNotSupported_keepsStoredCredentialAndProceedsWithoutReResolving() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext context = collectionContext();

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(context));
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(context)).thenReturn(collectionResult(1, 0, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(integrationTokenClient).ensure(UUID.fromString(projectId()), CollectionProvider.SLACK);
        verify(projectIntegrationService, never()).resolveFetchRequest(any(), eq(CollectionProvider.SLACK));
        // 저장된 자격증명 그대로인 원본 context가 그대로 넘어간다 — 재조립되지 않는다
        verify(pipelineService).collectIncremental(context);
    }

    @Test
    void handle_slackTokenEnsureFails_excludesSlackButContinuesGitHubCollection() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext context = collectionContext();
        ProjectCollectionContext contextWithoutSlack = context.without(CollectionProvider.SLACK);

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(context));
        when(integrationTokenClient.ensure(UUID.fromString(projectId()), CollectionProvider.SLACK))
                .thenReturn(IntegrationTokenClient.TokenStatus.FAILED);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(contextWithoutSlack)).thenReturn(collectionResult(1, 0, 0));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(pipelineService).collectIncremental(contextWithoutSlack);
    }

    @Test
    void handle_collectionFailure_marksDeliveryFailed() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext context = collectionContext();

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(context));
        when(pipelineService.collectIncremental(context))
                .thenThrow(new IllegalStateException("collection failed"));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(webhookDeliveryService).markFailed("delivery-1", "IllegalStateException: collection failed");
    }

    @Test
    void handle_executorRejection_releasesClaimForGitHubRetry() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext context = collectionContext();
        TaskExecutorRejector rejectingExecutor = new TaskExecutorRejector();
        GitHubWebhookService rejectingService = new GitHubWebhookService(
                new ObjectMapper(),
                verifier,
                webhookDeliveryService,
                installationTokenClient,
                integrationTokenClient,
                projectIntegrationService,
                pipelineService,
                rejectingExecutor,
                new ProjectCollectionSerializer(8)
        );

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(context));

        assertThrows(RejectedExecutionException.class, () -> rejectingService.handle(headers, payload));

        verify(webhookDeliveryService).releaseClaim("delivery-1");
        verify(webhookDeliveryService, never()).markFailed(anyString(), anyString());
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Hub-Signature-256", "sig");
        headers.set("X-GitHub-Event", "pull_request");
        headers.set("X-GitHub-Delivery", "delivery-1");
        return headers;
    }

    private String payload(boolean merged, String action) {
        return """
                {
                  "action": "%s",
                  "pull_request": { "merged": %s },
                  "repository": { "full_name": "owner/repo", "id": 123 },
                  "installation": { "id": 456 }
                }
                """.formatted(action, merged);
    }

    private ProjectCollectionContext collectionContext() {
        // 이 fixture는 GitHub webhook 흐름 자체를 검증하는 테스트에서 공용으로 쓴다 — Jira 토큰
        // 보장·재해석은 별도 fixture(collectionContextWithJira)로 검증하므로 Jira는 비워 둔다.
        return new ProjectCollectionContext(projectId(), Map.of(
                CollectionProvider.GITHUB, new RawFetchRequest("Bearer gh", "owner/repo", Map.of()),
                CollectionProvider.SLACK, new RawFetchRequest("Bearer slack", null, Map.of())
        ));
    }

    private ProjectCollectionContext collectionContextWithJira(RawFetchRequest jira) {
        return collectionContext().with(CollectionProvider.JIRA, jira);
    }

    private RawFetchRequest jiraRequest(String credentials) {
        return new RawFetchRequest(credentials, "PROJ", Map.of("baseUrl", "https://jira.example.com"));
    }

    private CollectionResult collectionResult(int github, int jira, int slack) {
        return new CollectionResult(Map.of(
                CollectionProvider.GITHUB, github,
                CollectionProvider.JIRA, jira,
                CollectionProvider.SLACK, slack
        ));
    }

    private String projectId() {
        return "11111111-1111-1111-1111-111111111111";
    }

    private static class TaskExecutorRejector extends SyncTaskExecutor {
        @Override
        public void execute(Runnable task) {
            throw new RejectedExecutionException("queue full");
        }
    }
}
