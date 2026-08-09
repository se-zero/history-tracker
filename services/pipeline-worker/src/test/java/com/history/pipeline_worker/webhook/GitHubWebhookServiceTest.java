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
        // GitHub는 별도 installation token 플로우로 이미 처리되므로, 일반화된 토큰 확보 루프
        // 대상에서 제외되어야 한다 (재조회 endpoint가 없는 provider라 404를 유발할 뿐이다).
        verify(integrationTokenClient, never()).ensureToken(any(), eq(CollectionProvider.GITHUB));
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
        when(integrationTokenClient.ensureToken(UUID.fromString(projectId()), CollectionProvider.JIRA)).thenReturn(true);
        when(projectIntegrationService.resolveFetchRequest(UUID.fromString(projectId()), CollectionProvider.JIRA))
                .thenReturn(refreshedJira);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(expectedContext)).thenReturn(collectionResult(1, 2, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(integrationTokenClient).ensureToken(UUID.fromString(projectId()), CollectionProvider.JIRA);
        verify(pipelineService).collectIncremental(expectedContext);
    }

    @Test
    void handle_readyWithLinearIntegration_ensuresLinearTokenAndReResolvesLinearContext() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext contextWithStaleLinear = collectionContextWithLinear(linearRequest("Bearer stale-linear-token"));
        Optional<RawFetchRequest> refreshedLinear = Optional.of(linearRequest("Bearer fresh-linear-token"));
        ProjectCollectionContext expectedContext =
                contextWithStaleLinear.with(CollectionProvider.LINEAR, refreshedLinear.orElseThrow());

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(contextWithStaleLinear));
        when(integrationTokenClient.ensureToken(UUID.fromString(projectId()), CollectionProvider.LINEAR)).thenReturn(true);
        when(projectIntegrationService.resolveFetchRequest(UUID.fromString(projectId()), CollectionProvider.LINEAR))
                .thenReturn(refreshedLinear);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(expectedContext)).thenReturn(collectionResult(1, 2, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(integrationTokenClient).ensureToken(UUID.fromString(projectId()), CollectionProvider.LINEAR);
        verify(pipelineService).collectIncremental(expectedContext);
    }

    // provider 순회를 일반형으로 바꾸면 Slack처럼 만료 토큰 개념이 없는 provider도 같은 확보 endpoint를
    // 탄다. 이 404를 Jira 전용이었던 "실패 → 제외" 해석으로 처리하면 Slack이 매 webhook마다 context에서
    // 빠져 Slack 수집이 영구히 멈춘다. 404는 "이 provider는 갱신할 게 없다"는 뜻이므로 기존 요청을 그대로
    // 두고 계속 진행해야 한다 — 이 케이스가 그 회귀를 고정한다.
    @Test
    void handle_tokenEnsure404NotApplicable_keepsExistingRequestAndDoesNotBreakCollection() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext context = collectionContext(); // github + slack

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(context));
        when(integrationTokenClient.ensureToken(UUID.fromString(projectId()), CollectionProvider.SLACK)).thenReturn(false);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(context)).thenReturn(collectionResult(1, 0, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(pipelineService).collectIncremental(context); // slack 요청이 그대로인 context로 수집됨
        verify(projectIntegrationService, never()).resolveFetchRequest(any(), eq(CollectionProvider.SLACK));
    }

    // 일반화 전에는 Jira의 404를 "실패"로 해석해 Jira를 context에서 제거했다. 일반화 후에는 404가
    // provider 공통으로 "갱신 수단 없음"을 뜻하므로, Jira도 예외 없이는 제거하지 않고 기존(만료됐을 수
    // 있는) 요청을 그대로 유지한 채 진행한다.
    @Test
    void handle_jiraTokenEnsure404NotApplicable_keepsStaleJiraRequestAndContinuesCollection() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext contextWithJira = collectionContextWithJira(jiraRequest("Bearer stale-jira-token"));

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(contextWithJira));
        when(integrationTokenClient.ensureToken(UUID.fromString(projectId()), CollectionProvider.JIRA)).thenReturn(false);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(contextWithJira)).thenReturn(collectionResult(1, 2, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(pipelineService).collectIncremental(contextWithJira);
        verify(projectIntegrationService, never()).resolveFetchRequest(any(), any());
    }

    @Test
    void handle_jiraTokenEnsureThrows_skipsJiraButContinuesGitHubCollection() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext contextWithJira = collectionContextWithJira(jiraRequest("Bearer stale-jira-token"));
        ProjectCollectionContext contextWithoutJira = contextWithJira.without(CollectionProvider.JIRA);

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(GitHubWebhookIntegrationResolution.ready(contextWithJira));
        when(integrationTokenClient.ensureToken(UUID.fromString(projectId()), CollectionProvider.JIRA))
                .thenThrow(new RuntimeException("backend 500"));
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(pipelineService.collectIncremental(contextWithoutJira)).thenReturn(collectionResult(1, 0, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(pipelineService).collectIncremental(contextWithoutJira);
        verify(projectIntegrationService, never()).resolveFetchRequest(any(), any());
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

    private ProjectCollectionContext collectionContextWithLinear(RawFetchRequest linear) {
        return collectionContext().with(CollectionProvider.LINEAR, linear);
    }

    private RawFetchRequest jiraRequest(String credentials) {
        return new RawFetchRequest(credentials, "PROJ", Map.of("baseUrl", "https://jira.example.com"));
    }

    private RawFetchRequest linearRequest(String credentials) {
        return new RawFetchRequest(credentials, "TEAM-1", Map.of());
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
