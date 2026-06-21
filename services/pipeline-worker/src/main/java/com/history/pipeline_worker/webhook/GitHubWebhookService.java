package com.history.pipeline_worker.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.collection.ProjectCollectionContext;
import com.history.pipeline_worker.collection.ProjectIntegrationService;
import com.history.pipeline_worker.collection.GitHubWebhookIntegrationResolution;
import com.history.pipeline_worker.pipeline.CollectionResult;
import com.history.pipeline_worker.pipeline.PipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class GitHubWebhookService {

    private final ObjectMapper objectMapper;
    private final GitHubWebhookVerifier verifier;
    private final WebhookDeliveryService webhookDeliveryService;
    private final GitHubInstallationTokenClient installationTokenClient;
    private final ProjectIntegrationService projectIntegrationService;
    private final PipelineService pipelineService;
    private final TaskExecutor taskExecutor;

    public GitHubWebhookService(
            ObjectMapper objectMapper,
            GitHubWebhookVerifier verifier,
            WebhookDeliveryService webhookDeliveryService,
            GitHubInstallationTokenClient installationTokenClient,
            ProjectIntegrationService projectIntegrationService,
            PipelineService pipelineService,
            @Qualifier("webhookTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.objectMapper = objectMapper;
        this.verifier = verifier;
        this.webhookDeliveryService = webhookDeliveryService;
        this.installationTokenClient = installationTokenClient;
        this.projectIntegrationService = projectIntegrationService;
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
        if (webhook.installationId() == null) {
            return new WebhookResult(WebhookStatus.BAD_REQUEST, "missing installation id");
        }

        GitHubWebhookIntegrationResolution resolution = projectIntegrationService
                .resolveGitHubPullRequestWebhook(webhook);
        if (resolution.status() == GitHubWebhookIntegrationResolution.Status.TOKEN_REFRESH_REQUIRED) {
            if (!installationTokenClient.ensureInstallationToken(webhook.installationId())) {
                return new WebhookResult(WebhookStatus.NOT_FOUND, "GitHub installation not found");
            }
            resolution = projectIntegrationService.resolveGitHubPullRequestWebhook(webhook);
        }
        if (resolution.status() != GitHubWebhookIntegrationResolution.Status.READY) {
            return new WebhookResult(WebhookStatus.NOT_FOUND, "no project integration found");
        }
        ProjectCollectionContext collectionContext = resolution.context();

        if (!webhookDeliveryService.tryClaim(deliveryId, collectionContext.projectId())) {
            return new WebhookResult(WebhookStatus.DUPLICATE, "duplicate delivery");
        }

        try {
            taskExecutor.execute(() -> runCollection(deliveryId, collectionContext));
        } catch (RejectedExecutionException e) {
            // executor 포화로 큐잉 실패 시 claim 해제 — GitHub 재전송의 재claim 허용 (FAILED로 굳히지 않음)
            webhookDeliveryService.releaseClaim(deliveryId);
            throw e;
        } catch (RuntimeException e) {
            webhookDeliveryService.markFailed(deliveryId, failureReason(e));
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
            webhookDeliveryService.markProcessed(deliveryId);
            log.info("Webhook-triggered collection completed: deliveryId={}, projectId={}, github={}, jira={}, slack={}",
                    deliveryId, context.projectId(), result.github(), result.jira(), result.slack());
        } catch (Exception e) {
            webhookDeliveryService.markFailed(deliveryId, failureReason(e));
            log.error("Webhook-triggered collection failed: deliveryId={}", deliveryId, e);
        }
    }

    private String failureReason(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return exception.getClass().getSimpleName() + ": " + message;
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
