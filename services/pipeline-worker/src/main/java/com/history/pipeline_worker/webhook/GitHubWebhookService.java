package com.history.pipeline_worker.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.collection.ProjectCollectionContext;
import com.history.pipeline_worker.collection.ProjectIntegrationResolver;
import com.history.pipeline_worker.pipeline.CollectionResult;
import com.history.pipeline_worker.pipeline.PipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GitHubWebhookService {

    private final ObjectMapper objectMapper;
    private final GitHubWebhookVerifier verifier;
    private final WebhookDeliveryStore deliveryStore;
    private final ProjectIntegrationResolver projectIntegrationResolver;
    private final PipelineService pipelineService;
    private final TaskExecutor taskExecutor;

    public GitHubWebhookService(
            ObjectMapper objectMapper,
            GitHubWebhookVerifier verifier,
            WebhookDeliveryStore deliveryStore,
            ProjectIntegrationResolver projectIntegrationResolver,
            PipelineService pipelineService,
            @Qualifier("webhookTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.objectMapper = objectMapper;
        this.verifier = verifier;
        this.deliveryStore = deliveryStore;
        this.projectIntegrationResolver = projectIntegrationResolver;
        this.pipelineService = pipelineService;
        this.taskExecutor = taskExecutor;
    }

    public WebhookResult handle(HttpHeaders headers, String payload) {
        String signature = headers.getFirst("X-Hub-Signature-256");
        if (!verifier.verify(payload, signature)) {
            return new WebhookResult(WebhookStatus.UNAUTHORIZED, "invalid signature");
        }

        String event = headers.getFirst("X-GitHub-Event");
        if (!"pull_request".equals(event)) {
            return new WebhookResult(WebhookStatus.IGNORED, "ignored event");
        }

        String deliveryId = headers.getFirst("X-GitHub-Delivery");
        if (deliveryId == null || deliveryId.isBlank()) {
            return new WebhookResult(WebhookStatus.BAD_REQUEST, "missing delivery id");
        }

        GitHubWebhookPayload webhook;
        try {
            webhook = parsePullRequestWebhook(payload);
        } catch (IllegalArgumentException e) {
            return new WebhookResult(WebhookStatus.BAD_REQUEST, e.getMessage());
        }

        if (!webhook.isMergedPullRequest()) {
            return new WebhookResult(WebhookStatus.IGNORED, "not a merged pull request");
        }

        ProjectCollectionContext collectionContext = projectIntegrationResolver
                .resolveGitHubPullRequestWebhook(webhook)
                .orElse(null);
        if (collectionContext == null) {
            return new WebhookResult(WebhookStatus.NOT_FOUND, "no project integration found");
        }

        if (!deliveryStore.tryClaim(deliveryId)) {
            return new WebhookResult(WebhookStatus.DUPLICATE, "duplicate delivery");
        }

        try {
            taskExecutor.execute(() -> runCollection(deliveryId, collectionContext));
        } catch (RuntimeException e) {
            deliveryStore.markFailed(deliveryId);
            throw e;
        }
        return new WebhookResult(WebhookStatus.ACCEPTED, "collection queued");
    }

    private GitHubWebhookPayload parsePullRequestWebhook(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String action = root.path("action").asText(null);
            boolean merged = root.path("pull_request").path("merged").asBoolean(false);
            JsonNode repository = root.path("repository");
            String repositoryFullName = repository.path("full_name").asText(null);
            if (repositoryFullName == null || repositoryFullName.isBlank()) {
                throw new IllegalArgumentException("missing repository full_name");
            }
            Long repositoryId = repository.path("id").canConvertToLong()
                    ? repository.path("id").asLong()
                    : null;
            JsonNode installation = root.path("installation");
            Long installationId = installation.path("id").canConvertToLong()
                    ? installation.path("id").asLong()
                    : null;
            return new GitHubWebhookPayload(action, merged, repositoryFullName, repositoryId, installationId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid payload", e);
        }
    }

    private void runCollection(String deliveryId, ProjectCollectionContext context) {
        try {
            CollectionResult result = pipelineService.collectIncremental(context);
            deliveryStore.markProcessed(deliveryId);
            log.info("Webhook-triggered collection completed: deliveryId={}, projectId={}, github={}, jira={}, slack={}",
                    deliveryId, context.projectId(), result.github(), result.jira(), result.slack());
        } catch (Exception e) {
            deliveryStore.markFailed(deliveryId);
            log.error("Webhook-triggered collection failed: deliveryId={}", deliveryId, e);
        }
    }

    public record WebhookResult(WebhookStatus status, String message) {}

    public enum WebhookStatus {
        ACCEPTED,
        IGNORED,
        DUPLICATE,
        NOT_FOUND,
        UNAUTHORIZED,
        BAD_REQUEST
    }
}
