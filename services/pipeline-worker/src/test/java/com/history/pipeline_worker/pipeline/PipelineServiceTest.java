package com.history.pipeline_worker.pipeline;

import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectCollectionContext;
import com.history.pipeline_worker.collection.SourceCollector;
import com.history.pipeline_worker.collection.SourceCollectorRegistry;
import com.history.pipeline_worker.dto.RawFetchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PipelineServiceTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";

    private final SourceCollector github = collector(CollectionProvider.GITHUB);
    private final SourceCollector jira = collector(CollectionProvider.JIRA);
    private final SourceCollector slack = collector(CollectionProvider.SLACK);
    private final PipelineService pipelineService =
            new PipelineService(new SourceCollectorRegistry(List.of(github, jira, slack)));

    @Test
    @DisplayName("컨텍스트에 담긴 provider만 수집하고 발행 건수를 provider별로 돌려준다")
    void collectIncremental_collectsEveryProviderInContext() {
        RawFetchRequest githubRequest = new RawFetchRequest("Bearer gh", "owner/repo", Map.of());
        RawFetchRequest slackRequest = new RawFetchRequest("Bearer slack", null, Map.of());
        when(github.collect(PROJECT_ID, githubRequest)).thenReturn(4);
        when(slack.collect(PROJECT_ID, slackRequest)).thenReturn(2);

        CollectionResult result = pipelineService.collectIncremental(new ProjectCollectionContext(
                PROJECT_ID,
                Map.of(CollectionProvider.GITHUB, githubRequest, CollectionProvider.SLACK, slackRequest)
        ));

        assertThat(result.of(CollectionProvider.GITHUB)).isEqualTo(4);
        assertThat(result.of(CollectionProvider.SLACK)).isEqualTo(2);
        assertThat(result.of(CollectionProvider.JIRA)).isZero();
        assertThat(result.total()).isEqualTo(6);
        verify(jira, never()).collect(anyString(), any());
    }

    @Test
    @DisplayName("수집 순서는 컨텍스트 구성 순서와 무관하게 provider 선언 순서를 따른다")
    void collectIncremental_runsProvidersInDeclarationOrder() {
        RawFetchRequest githubRequest = new RawFetchRequest("Bearer gh", "owner/repo", Map.of());
        RawFetchRequest jiraRequest = new RawFetchRequest("Bearer jira", "PLAT", Map.of());
        RawFetchRequest slackRequest = new RawFetchRequest("Bearer slack", null, Map.of());

        // 일부러 역순으로 넣어도 GitHub → Jira → Slack 순으로 돌아야 한다
        Map<CollectionProvider, RawFetchRequest> reversed = new LinkedHashMap<>();
        reversed.put(CollectionProvider.SLACK, slackRequest);
        reversed.put(CollectionProvider.JIRA, jiraRequest);
        reversed.put(CollectionProvider.GITHUB, githubRequest);

        pipelineService.collectIncremental(new ProjectCollectionContext(PROJECT_ID, reversed));

        InOrder inOrder = inOrder(github, jira, slack);
        inOrder.verify(github).collect(PROJECT_ID, githubRequest);
        inOrder.verify(jira).collect(PROJECT_ID, jiraRequest);
        inOrder.verify(slack).collect(PROJECT_ID, slackRequest);
    }

    @Test
    @DisplayName("한 provider가 실패하면 이후 provider는 돌지 않는다")
    void collectIncremental_propagatesFailureAndStops() {
        RawFetchRequest githubRequest = new RawFetchRequest("Bearer gh", "owner/repo", Map.of());
        RawFetchRequest slackRequest = new RawFetchRequest("Bearer slack", null, Map.of());
        when(github.collect(PROJECT_ID, githubRequest)).thenThrow(new IllegalStateException("publish failed"));

        assertThatThrownBy(() -> pipelineService.collectIncremental(new ProjectCollectionContext(
                PROJECT_ID,
                Map.of(CollectionProvider.GITHUB, githubRequest, CollectionProvider.SLACK, slackRequest)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish failed");
        verify(slack, never()).collect(anyString(), any());
    }

    @Test
    void collect_dispatchesToRegisteredCollector() {
        RawFetchRequest request = new RawFetchRequest("Bearer jira", "PLAT", Map.of());
        when(jira.collect(PROJECT_ID, request)).thenReturn(7);

        assertThat(pipelineService.collect(PROJECT_ID, CollectionProvider.JIRA, request)).isEqualTo(7);
    }

    private static SourceCollector collector(CollectionProvider provider) {
        SourceCollector collector = mock(SourceCollector.class);
        when(collector.provider()).thenReturn(provider);
        return collector;
    }
}
