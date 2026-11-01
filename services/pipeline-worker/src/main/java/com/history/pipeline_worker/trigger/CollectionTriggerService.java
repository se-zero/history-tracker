package com.history.pipeline_worker.trigger;

import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.IntegrationDisconnectedException;
import com.history.pipeline_worker.collection.ProjectIntegrationService;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.pipeline.PipelineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

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
        Optional<RawFetchRequest> request = projectIntegrationService.resolveFetchRequest(projectId, provider);
        if (request.isEmpty()) {
            return new TriggerResult(TriggerStatus.NOT_FOUND, "no project integration found");
        }

        taskExecutor.execute(() -> runCollection(provider, projectId, request.orElseThrow()));
        return new TriggerResult(TriggerStatus.ACCEPTED, "collection queued");
    }

    private void runCollection(CollectionProvider provider, UUID projectId, RawFetchRequest request) {
        try {
            pipelineService.collect(projectId.toString(), provider, request);
            log.info("Initial collection completed: projectId={}, provider={}", projectId, provider);
        } catch (IntegrationDisconnectedException exception) {
            // 수집 도중 사용자가 연동을 해제한 정상 경로 — ERROR로 남기면 해제할 때마다 오류 알람이 울린다.
            log.info("연동이 해제돼 초기 수집을 멈춤: projectId={}, provider={}", projectId, provider);
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
