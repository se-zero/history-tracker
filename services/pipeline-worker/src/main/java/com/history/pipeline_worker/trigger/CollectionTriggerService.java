package com.history.pipeline_worker.trigger;

import com.history.pipeline_worker.collection.CollectionProvider;
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
            @Qualifier("collectionTaskExecutor") TaskExecutor taskExecutor
    ) {
        this.projectIntegrationService = projectIntegrationService;
        this.pipelineService = pipelineService;
        // 장기 실행되는 초기 수집은 webhook 증분 수집과 풀을 분리한다(collectionTaskExecutor).
        // webhook 유실 방지를 위해 초기 수집이 webhook 풀을 점유하지 못하게 한다.
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
