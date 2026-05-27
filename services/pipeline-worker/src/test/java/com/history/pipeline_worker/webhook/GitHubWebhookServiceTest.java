package com.history.pipeline_worker.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.collection.GitHubIntegration;
import com.history.pipeline_worker.collection.JiraIntegration;
import com.history.pipeline_worker.collection.ProjectCollectionContext;
import com.history.pipeline_worker.collection.ProjectIntegrationService;
import com.history.pipeline_worker.collection.SlackIntegration;
import com.history.pipeline_worker.pipeline.CollectionResult;
import com.history.pipeline_worker.pipeline.PipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.HttpHeaders;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class GitHubWebhookServiceTest {

    private GitHubWebhookVerifier verifier;
    private WebhookDeliveryService webhookDeliveryService;
    private ProjectIntegrationService projectIntegrationService;
    private PipelineService pipelineService;
    private GitHubWebhookService service;

    @BeforeEach
    void setUp() {
        verifier = mock(GitHubWebhookVerifier.class);
        webhookDeliveryService = mock(WebhookDeliveryService.class);
        projectIntegrationService = mock(ProjectIntegrationService.class);
        pipelineService = mock(PipelineService.class);
        service = new GitHubWebhookService(
                new ObjectMapper(),
                verifier,
                webhookDeliveryService,
                projectIntegrationService,
                pipelineService,
                new SyncTaskExecutor()
        );
    }

    @Test
    void handle_invalidSignature_returnsUnauthorized() {
        HttpHeaders headers = headers();
        when(verifier.verify(payload(true, "closed"), "sig")).thenReturn(false);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload(true, "closed"));

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.UNAUTHORIZED);
        verifyNoInteractions(webhookDeliveryService, projectIntegrationService, pipelineService);
    }

    @Test
    void handle_nonPullRequestEvent_isIgnored() {
        HttpHeaders headers = headers();
        headers.set("X-GitHub-Event", "push");
        String payload = payload(true, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.IGNORED);
        verifyNoInteractions(webhookDeliveryService, projectIntegrationService, pipelineService);
    }

    @Test
    void handle_closedUnmergedPullRequest_isIgnored() {
        HttpHeaders headers = headers();
        String payload = payload(false, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.IGNORED);
        verify(webhookDeliveryService, never()).tryClaim(anyString(), anyString());
        verifyNoInteractions(projectIntegrationService, pipelineService);
    }

    @Test
    void handle_noProjectIntegration_returnsNotFound() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(Optional.empty());

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.NOT_FOUND);
        verify(webhookDeliveryService, never()).tryClaim(anyString(), anyString());
        verifyNoInteractions(pipelineService);
    }

    @Test
    void handle_duplicateDelivery_doesNotRunPipeline() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(Optional.of(collectionContext()));
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
                .thenReturn(Optional.of(context));
        when(pipelineService.collectIncremental(context)).thenReturn(new CollectionResult(1, 2, 3));

        GitHubWebhookService.WebhookResult result = service.handle(headers, payload);

        assertThat(result.status()).isEqualTo(GitHubWebhookService.WebhookStatus.ACCEPTED);
        verify(pipelineService).collectIncremental(context);
        verify(webhookDeliveryService).markProcessed("delivery-1");
    }

    @Test
    void handle_collectionFailure_marksDeliveryFailed() {
        HttpHeaders headers = headers();
        String payload = payload(true, "closed");
        ProjectCollectionContext context = collectionContext();

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(Optional.of(context));
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
                projectIntegrationService,
                pipelineService,
                rejectingExecutor
        );

        when(verifier.verify(payload, "sig")).thenReturn(true);
        when(webhookDeliveryService.tryClaim("delivery-1", projectId())).thenReturn(true);
        when(projectIntegrationService.resolveGitHubPullRequestWebhook(any()))
                .thenReturn(Optional.of(context));

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
        return new ProjectCollectionContext(
                projectId(),
                new GitHubIntegration("Bearer gh", "owner/repo", null),
                Optional.of(new JiraIntegration("jira:token", "PROJ", "https://jira.example.com")),
                Optional.of(new SlackIntegration("Bearer slack"))
        );
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
