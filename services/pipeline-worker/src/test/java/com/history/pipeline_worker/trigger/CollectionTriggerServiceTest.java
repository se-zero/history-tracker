package com.history.pipeline_worker.trigger;

import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.GitHubIntegration;
import com.history.pipeline_worker.collection.JiraIntegration;
import com.history.pipeline_worker.collection.ProjectIntegrationService;
import com.history.pipeline_worker.collection.SlackIntegration;
import com.history.pipeline_worker.pipeline.PipelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CollectionTriggerServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ProjectIntegrationService projectIntegrationService;
    private PipelineService pipelineService;
    private TaskExecutor taskExecutor;
    private CollectionTriggerService service;

    @BeforeEach
    void setUp() {
        projectIntegrationService = mock(ProjectIntegrationService.class);
        pipelineService = mock(PipelineService.class);
        taskExecutor = mock(TaskExecutor.class);
        service = new CollectionTriggerService(projectIntegrationService, pipelineService, taskExecutor);
    }

    @Test
    void triggerGitHub_queuesOnlyGitHubCollection() {
        GitHubIntegration integration = new GitHubIntegration("Bearer gh", "owner/repo", null);
        when(projectIntegrationService.resolveGitHub(PROJECT_ID)).thenReturn(Optional.of(integration));

        CollectionTriggerService.TriggerResult result = service.trigger(CollectionProvider.GITHUB, PROJECT_ID);

        assertThat(result.status()).isEqualTo(CollectionTriggerService.TriggerStatus.ACCEPTED);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(task.capture());
        verifyNoInteractions(pipelineService);

        task.getValue().run();

        verify(pipelineService).normalizeGitHub(PROJECT_ID.toString(), integration.toRawFetchRequest());
        verify(pipelineService, never()).normalizeJira(anyString(), any());
        verify(pipelineService, never()).normalizeSlack(anyString(), any());
    }

    @Test
    void triggerJira_queuesOnlyJiraCollection() {
        JiraIntegration integration = new JiraIntegration("jira:token", "PLAT", "https://jira.example.com");
        when(projectIntegrationService.resolveJira(PROJECT_ID)).thenReturn(Optional.of(integration));

        CollectionTriggerService.TriggerResult result = service.trigger(CollectionProvider.JIRA, PROJECT_ID);

        assertThat(result.status()).isEqualTo(CollectionTriggerService.TriggerStatus.ACCEPTED);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(task.capture());
        task.getValue().run();
        verify(pipelineService).normalizeJira(PROJECT_ID.toString(), integration.toRawFetchRequest());
        verify(pipelineService, never()).normalizeGitHub(anyString(), any());
        verify(pipelineService, never()).normalizeSlack(anyString(), any());
    }

    @Test
    void triggerSlack_queuesOnlySlackCollection() {
        SlackIntegration integration = new SlackIntegration("Bearer slack");
        when(projectIntegrationService.resolveSlack(PROJECT_ID)).thenReturn(Optional.of(integration));

        CollectionTriggerService.TriggerResult result = service.trigger(CollectionProvider.SLACK, PROJECT_ID);

        assertThat(result.status()).isEqualTo(CollectionTriggerService.TriggerStatus.ACCEPTED);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskExecutor).execute(task.capture());
        task.getValue().run();
        verify(pipelineService).normalizeSlack(PROJECT_ID.toString(), integration.toRawFetchRequest());
        verify(pipelineService, never()).normalizeGitHub(anyString(), any());
        verify(pipelineService, never()).normalizeJira(anyString(), any());
    }

    @Test
    void trigger_missingIntegration_returnsNotFoundWithoutQueueing() {
        when(projectIntegrationService.resolveGitHub(PROJECT_ID)).thenReturn(Optional.empty());

        CollectionTriggerService.TriggerResult result = service.trigger(CollectionProvider.GITHUB, PROJECT_ID);

        assertThat(result.status()).isEqualTo(CollectionTriggerService.TriggerStatus.NOT_FOUND);
        verifyNoInteractions(taskExecutor, pipelineService);
    }

    @Test
    void trigger_executorRejection_isPropagated() {
        GitHubIntegration integration = new GitHubIntegration("Bearer gh", "owner/repo", null);
        when(projectIntegrationService.resolveGitHub(PROJECT_ID)).thenReturn(Optional.of(integration));
        doThrow(new RejectedExecutionException("queue full")).when(taskExecutor).execute(any(Runnable.class));

        assertThatThrownBy(() -> service.trigger(CollectionProvider.GITHUB, PROJECT_ID))
                .isInstanceOf(RejectedExecutionException.class)
                .hasMessage("queue full");

        verifyNoInteractions(pipelineService);
    }
}
