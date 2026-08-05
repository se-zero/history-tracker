package com.history.pipeline_worker.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.JiraTokenClient;
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

import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
public class GitHubWebhookService {

    private final ObjectMapper objectMapper;
    private final GitHubWebhookVerifier verifier;
    private final WebhookDeliveryService webhookDeliveryService;
    private final GitHubInstallationTokenClient installationTokenClient;
    private final JiraTokenClient jiraTokenClient;
    private final ProjectIntegrationService projectIntegrationService;
    private final PipelineService pipelineService;
    private final TaskExecutor taskExecutor;
    private final ProjectCollectionSerializer collectionSerializer;

    public GitHubWebhookService(
            ObjectMapper objectMapper,
            GitHubWebhookVerifier verifier,
            WebhookDeliveryService webhookDeliveryService,
            GitHubInstallationTokenClient installationTokenClient,
            JiraTokenClient jiraTokenClient,
            ProjectIntegrationService projectIntegrationService,
            PipelineService pipelineService,
            @Qualifier("webhookTaskExecutor") TaskExecutor taskExecutor,
            ProjectCollectionSerializer collectionSerializer
    ) {
        this.objectMapper = objectMapper;
        this.verifier = verifier;
        this.webhookDeliveryService = webhookDeliveryService;
        this.installationTokenClient = installationTokenClient;
        this.jiraTokenClient = jiraTokenClient;
        this.projectIntegrationService = projectIntegrationService;
        this.pipelineService = pipelineService;
        this.taskExecutor = taskExecutor;
        this.collectionSerializer = collectionSerializer;
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
        ProjectCollectionContext collectionContext = ensureFreshJiraToken(resolution.context());

        if (!webhookDeliveryService.tryClaim(deliveryId, collectionContext.projectId())) {
            return new WebhookResult(WebhookStatus.DUPLICATE, "duplicate delivery");
        }

        try {
            taskExecutor.execute(() -> runCollection(deliveryId, collectionContext));
        } catch (RejectedExecutionException e) {
            // executor 포화로 큐잉 실패 시 claim 해제 — GitHub 재전송의 재claim 허용 (FAILED로 굳히지 않음)
            // 반복 발생 시 P-2 2단계(webhook 동시성 증가) 신호 — webhook 풀 큐(capacity) 초과
            log.warn("webhook executor 포화로 수집 거부(큐 용량 초과): deliveryId={}, projectId={}",
                    deliveryId, collectionContext.projectId());
            webhookDeliveryService.releaseClaim(deliveryId);
            throw e;
        } catch (RuntimeException e) {
            webhookDeliveryService.markFailed(deliveryId, failureReason(e));
            throw e;
        }
        return new WebhookResult(WebhookStatus.ACCEPTED, "collection queued");
    }

    // Jira는 선택 연동이다 — 토큰 확보에 실패해도 GitHub·Slack 수집은 계속 진행해야 하므로 예외를
    // 삼키고 Jira만 컨텍스트에서 뺀다. 죽은 토큰으로 시도해 401을 내는 것보다 낫다.
    private ProjectCollectionContext ensureFreshJiraToken(ProjectCollectionContext context) {
        if (context.request(CollectionProvider.JIRA).isEmpty()) {
            return context;
        }
        UUID projectId = UUID.fromString(context.projectId());
        if (!ensureJiraToken(projectId)) {
            log.warn("Jira 토큰 확보 실패로 이번 수집에서 Jira를 건너뜁니다: projectId={}", context.projectId());
            return context.without(CollectionProvider.JIRA);
        }
        // 갱신된 토큰으로 Jira 요청만 다시 만든다 — 재해석이 실패하면 Jira만 빼고 진행한다.
        return projectIntegrationService.resolveFetchRequest(projectId, CollectionProvider.JIRA)
                .map(request -> context.with(CollectionProvider.JIRA, request))
                .orElseGet(() -> context.without(CollectionProvider.JIRA));
    }

    private boolean ensureJiraToken(UUID projectId) {
        try {
            return jiraTokenClient.ensureJiraToken(projectId);
        } catch (RuntimeException e) {
            log.warn("Jira 토큰 확보 요청이 실패했습니다: projectId={}", projectId, e);
            return false;
        }
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
            // 같은 프로젝트의 동시 수집을 직렬화 — webhook 풀 동시성>1에서 중복 풀스캔 방지
            CollectionResult result = collectionSerializer.callExclusively(
                    context.projectId(),
                    () -> pipelineService.collectIncremental(context));
            webhookDeliveryService.markProcessed(deliveryId);
            log.info("Webhook-triggered collection completed: deliveryId={}, projectId={}, published={}",
                    deliveryId, context.projectId(), result.published());
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
