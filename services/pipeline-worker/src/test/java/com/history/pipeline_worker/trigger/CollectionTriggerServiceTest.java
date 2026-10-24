package com.history.pipeline_worker.trigger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.IntegrationDisconnectedException;
import com.history.pipeline_worker.collection.ProjectIntegrationService;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.pipeline.PipelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CollectionTriggerServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ProjectIntegrationService projectIntegrationService;
    private PipelineService pipelineService;
    private TaskExecutor taskExecutor;
    private CollectionTriggerService service;
    private Logger serviceLogger;
    private Level originalLogLevel;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        projectIntegrationService = mock(ProjectIntegrationService.class);
        pipelineService = mock(PipelineService.class);
        taskExecutor = mock(TaskExecutor.class);
        service = new CollectionTriggerService(projectIntegrationService, pipelineService, taskExecutor);

        // 로그 수준을 확인하는 테스트용 — 다른 테스트가 바꿔 둔 로거 수준에 흔들리지 않게 INFO로 고정했다가 되돌린다
        serviceLogger = (Logger) LoggerFactory.getLogger(CollectionTriggerService.class);
        originalLogLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logs);
        serviceLogger.setLevel(originalLogLevel);
    }

    @Test
    void trigger_queuesOnlyRequestedProvider() {
        RawFetchRequest request = new RawFetchRequest("Bearer gh", "owner/repo", Map.of());
        when(projectIntegrationService.resolveFetchRequest(PROJECT_ID, CollectionProvider.GITHUB))
                .thenReturn(Optional.of(request));

        CollectionTriggerService.TriggerResult result = service.trigger(CollectionProvider.GITHUB, PROJECT_ID);

        assertThat(result.status()).isEqualTo(CollectionTriggerService.TriggerStatus.ACCEPTED);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(task.capture());
        // 트리거는 수집 완료를 기다리지 않는다 — 큐잉 시점에는 아직 수집이 시작되지 않아야 한다
        verifyNoInteractions(pipelineService);

        task.getValue().run();

        verify(pipelineService).collect(PROJECT_ID.toString(), CollectionProvider.GITHUB, request);
    }

    @Test
    void trigger_missingIntegration_returnsNotFoundWithoutQueueing() {
        when(projectIntegrationService.resolveFetchRequest(PROJECT_ID, CollectionProvider.JIRA))
                .thenReturn(Optional.empty());

        CollectionTriggerService.TriggerResult result = service.trigger(CollectionProvider.JIRA, PROJECT_ID);

        assertThat(result.status()).isEqualTo(CollectionTriggerService.TriggerStatus.NOT_FOUND);
        verifyNoInteractions(taskExecutor, pipelineService);
    }

    @Test
    void trigger_collectionFailure_isSwallowedByTask() {
        RawFetchRequest request = new RawFetchRequest("Bearer slack", null, Map.of());
        when(projectIntegrationService.resolveFetchRequest(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Optional.of(request));
        when(pipelineService.collect(PROJECT_ID.toString(), CollectionProvider.SLACK, request))
                .thenThrow(new IllegalStateException("collection failed"));

        service.trigger(CollectionProvider.SLACK, PROJECT_ID);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(task.capture());
        // 비동기 초기 수집 실패는 로그로만 남긴다 — 이미 202를 반환한 뒤라 던져봐야 받을 곳이 없다
        task.getValue().run();
    }

    // 연동 해제로 멈춘 수집은 장애가 아니다 — ERROR로 남기면 해제할 때마다 오류 알람이 울린다.
    @Test
    void trigger_integrationDisconnected_isSwallowedAndLoggedAsInfo() {
        RawFetchRequest request = new RawFetchRequest("Bearer gh", "owner/repo", Map.of());
        when(projectIntegrationService.resolveFetchRequest(PROJECT_ID, CollectionProvider.GITHUB))
                .thenReturn(Optional.of(request));
        when(pipelineService.collect(PROJECT_ID.toString(), CollectionProvider.GITHUB, request))
                .thenThrow(new IntegrationDisconnectedException("integration disconnected"));

        service.trigger(CollectionProvider.GITHUB, PROJECT_ID);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(task.capture());
        assertThatCode(() -> task.getValue().run()).doesNotThrowAnyException();

        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.INFO);
    }

    @Test
    void trigger_otherCollectionFailure_isLoggedAsError() {
        RawFetchRequest request = new RawFetchRequest("Bearer slack", null, Map.of());
        when(projectIntegrationService.resolveFetchRequest(PROJECT_ID, CollectionProvider.SLACK))
                .thenReturn(Optional.of(request));
        when(pipelineService.collect(PROJECT_ID.toString(), CollectionProvider.SLACK, request))
                .thenThrow(new IllegalStateException("collection failed"));

        service.trigger(CollectionProvider.SLACK, PROJECT_ID);

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(task.capture());
        assertThatCode(() -> task.getValue().run()).doesNotThrowAnyException();

        assertThat(logs.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void trigger_executorRejection_isPropagated() {
        RawFetchRequest request = new RawFetchRequest("Bearer gh", "owner/repo", Map.of());
        when(projectIntegrationService.resolveFetchRequest(PROJECT_ID, CollectionProvider.GITHUB))
                .thenReturn(Optional.of(request));
        doThrow(new RejectedExecutionException("queue full")).when(taskExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.trigger(CollectionProvider.GITHUB, PROJECT_ID))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("queue full");

        verifyNoInteractions(pipelineService);
    }
}
