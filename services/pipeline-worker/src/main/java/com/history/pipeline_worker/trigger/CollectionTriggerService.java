package com.history.pipeline_worker.trigger;

import com.history.pipeline_worker.collection.ProjectIntegrationService;
import com.history.pipeline_worker.pipeline.PipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
public class CollectionTriggerService {

    private final ProjectIntegrationService projectIntegrationService;
    private final PipelineService pipelineService;
    private final TaskExecutor taskExecutor;

    public CollectionTriggerService(
            ProjectIntegrationService projectIntegrationService,
            PipelineService pipelineService,
            @Qualifier("webhookTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.projectIntegrationService = projectIntegrationService;
        this.pipelineService = pipelineService;
        // 초기 수집과 webhook이 현재 같은 용량 제한을 공유한다. 부하가 분리되면 전용 executor로 교체한다.
        this.taskExecutor = taskExecutor;
    }

    public TriggerResult trigger(CollectionProvider provider, UUID projectId) {
        return switch (provider) {
            case GITHUB -> queue(
                    provider,
                    projectId,
                    projectIntegrationService.resolveGitHub(projectId),
                    integration -> pipelineService.normalizeGitHub(
                            projectId.toString(),
                            integration.toRawFetchRequest()
                    )
            );
            case JIRA -> queue(
                    provider,
                    projectId,
                    projectIntegrationService.resolveJira(projectId),
                    integration -> pipelineService.normalizeJira(
                            projectId.toString(),
                            integration.toRawFetchRequest()
                    )
            );
            case SLACK -> queue(
                    provider,
                    projectId,
                    projectIntegrationService.resolveSlack(projectId),
                    integration -> pipelineService.normalizeSlack(
                            projectId.toString(),
                            integration.toRawFetchRequest()
                    )
            );
        };
    }

    private <T> TriggerResult queue(
            CollectionProvider provider,
            UUID projectId,
            Optional<T> integration,
            Consumer<T> collection
    ) {
        if (integration.isEmpty()) {
            return new TriggerResult(TriggerStatus.NOT_FOUND, "no project integration found");
        }

        taskExecutor.execute(() -> runCollection(provider, projectId, integration.orElseThrow(), collection));
        return new TriggerResult(TriggerStatus.ACCEPTED, "collection queued");
    }

    private <T> void runCollection(
            CollectionProvider provider,
            UUID projectId,
            T integration,
            Consumer<T> collection
    ) {
        try {
            collection.accept(integration);
            log.info("Initial collection completed: projectId={}, provider={}", projectId, provider);
        } catch (Exception exception) {
            log.error("Initial collection failed: projectId={}, provider={}", projectId, provider, exception);
        }
    }

    public record TriggerResult(TriggerStatus status, String message) {}

    public enum TriggerStatus {
        ACCEPTED,
        NOT_FOUND
    }
}
