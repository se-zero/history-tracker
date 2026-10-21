package com.history.pipeline_worker.pipeline;

import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.CollectionRunContext;
import com.history.pipeline_worker.collection.IntegrationDisconnectedException;
import com.history.pipeline_worker.collection.ProjectCollectionContext;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.collection.SourceCollector;
import com.history.pipeline_worker.collection.SourceCollectorRegistry;
import com.history.pipeline_worker.dto.RawFetchRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PipelineServiceTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String GITHUB_INTEGRATION_ID = "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1";
    private static final String JIRA_INTEGRATION_ID = "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2";
    private static final String SLACK_INTEGRATION_ID = "c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3";

    private final SourceCollector github = collector(CollectionProvider.GITHUB);
    private final SourceCollector jira = collector(CollectionProvider.JIRA);
    private final SourceCollector slack = collector(CollectionProvider.SLACK);
    // CollectionRunContext는 실제 객체 — collector가 도는 동안 범위가 열렸다 닫히는지를 직접 관찰한다
    private final CollectionRunContext runContext =
            new CollectionRunContext(mock(ProjectIntegrationRepository.class));
    private final PipelineService pipelineService =
            new PipelineService(new SourceCollectorRegistry(List.of(github, jira, slack)), runContext);

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

    @Test
    @DisplayName("collect는 collector가 도는 동안 요청의 integrationId로 범위를 열고, 끝나면 닫는다")
    void collect_opensScopeForRequestIntegrationIdWhileCollecting() {
        RawFetchRequest request = new RawFetchRequest("Bearer gh", "owner/repo", Map.of())
                .withIntegrationId(GITHUB_INTEGRATION_ID);
        AtomicReference<Optional<String>> seenDuringCollect = new AtomicReference<>();
        when(github.collect(PROJECT_ID, request)).thenAnswer(invocation -> {
            seenDuringCollect.set(runContext.currentIntegrationId());
            return 3;
        });

        int published = pipelineService.collect(PROJECT_ID, CollectionProvider.GITHUB, request);

        assertThat(published).isEqualTo(3);
        assertThat(seenDuringCollect.get()).contains(GITHUB_INTEGRATION_ID);
        assertThat(runContext.currentIntegrationId()).isEmpty();
    }

    // 풀 스레드는 재사용된다 — 실패한 수집의 범위가 남으면 같은 스레드의 다음 작업이 엉뚱한 연동에 묶인다.
    @Test
    @DisplayName("collector가 예외를 던져도 범위를 닫는다")
    void collect_collectorThrows_stillClosesScope() {
        RawFetchRequest request = new RawFetchRequest("Bearer gh", "owner/repo", Map.of())
                .withIntegrationId(GITHUB_INTEGRATION_ID);
        AtomicReference<Optional<String>> seenDuringCollect = new AtomicReference<>();
        when(github.collect(PROJECT_ID, request)).thenAnswer(invocation -> {
            seenDuringCollect.set(runContext.currentIntegrationId());
            throw new IllegalStateException("publish failed");
        });

        assertThatThrownBy(() -> pipelineService.collect(PROJECT_ID, CollectionProvider.GITHUB, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish failed");

        assertThat(seenDuringCollect.get()).contains(GITHUB_INTEGRATION_ID);
        assertThat(runContext.currentIntegrationId()).isEmpty();
    }

    @Test
    @DisplayName("요청에 integrationId가 없으면 범위를 열지 않는다")
    void collect_requestWithoutIntegrationId_opensNoScope() {
        RawFetchRequest request = new RawFetchRequest("Bearer jira", "PLAT", Map.of());
        AtomicReference<Optional<String>> seenDuringCollect = new AtomicReference<>();
        when(jira.collect(PROJECT_ID, request)).thenAnswer(invocation -> {
            seenDuringCollect.set(runContext.currentIntegrationId());
            return 1;
        });

        pipelineService.collect(PROJECT_ID, CollectionProvider.JIRA, request);

        assertThat(seenDuringCollect.get()).isEmpty();
    }

    // webhook 컨텍스트의 요청은 provider마다 다른 연동 행의 id를 갖는다 — 한 범위로 뭉개면 엉뚱한 연동을 확인한다.
    @Test
    @DisplayName("collectIncremental은 provider마다 자기 요청의 integrationId로 범위를 연다")
    void collectIncremental_opensEachProvidersOwnScope() {
        RawFetchRequest githubRequest = new RawFetchRequest("Bearer gh", "owner/repo", Map.of())
                .withIntegrationId(GITHUB_INTEGRATION_ID);
        RawFetchRequest jiraRequest = new RawFetchRequest("Bearer jira", "PLAT", Map.of())
                .withIntegrationId(JIRA_INTEGRATION_ID);
        Map<CollectionProvider, Optional<String>> seenDuringCollect = new EnumMap<>(CollectionProvider.class);
        when(github.collect(PROJECT_ID, githubRequest)).thenAnswer(invocation -> {
            seenDuringCollect.put(CollectionProvider.GITHUB, runContext.currentIntegrationId());
            return 1;
        });
        when(jira.collect(PROJECT_ID, jiraRequest)).thenAnswer(invocation -> {
            seenDuringCollect.put(CollectionProvider.JIRA, runContext.currentIntegrationId());
            return 1;
        });

        pipelineService.collectIncremental(new ProjectCollectionContext(
                PROJECT_ID,
                Map.of(CollectionProvider.GITHUB, githubRequest, CollectionProvider.JIRA, jiraRequest)
        ));

        assertThat(seenDuringCollect)
                .containsEntry(CollectionProvider.GITHUB, Optional.of(GITHUB_INTEGRATION_ID))
                .containsEntry(CollectionProvider.JIRA, Optional.of(JIRA_INTEGRATION_ID));
        assertThat(runContext.currentIntegrationId()).isEmpty();
    }

    @Test
    @DisplayName("연동이 해제된 provider는 결과에서 빼고 다음 provider를 계속 수집한다")
    void collectIncremental_disconnectedProvider_isSkippedAndNextProviderContinues() {
        RawFetchRequest githubRequest = new RawFetchRequest("Bearer gh", "owner/repo", Map.of())
                .withIntegrationId(GITHUB_INTEGRATION_ID);
        RawFetchRequest jiraRequest = new RawFetchRequest("Bearer jira", "PLAT", Map.of())
                .withIntegrationId(JIRA_INTEGRATION_ID);
        RawFetchRequest slackRequest = new RawFetchRequest("Bearer slack", null, Map.of())
                .withIntegrationId(SLACK_INTEGRATION_ID);
        when(github.collect(PROJECT_ID, githubRequest)).thenReturn(4);
        when(jira.collect(PROJECT_ID, jiraRequest))
                .thenThrow(new IntegrationDisconnectedException("jira integration disconnected"));
        when(slack.collect(PROJECT_ID, slackRequest)).thenReturn(2);

        CollectionResult result = pipelineService.collectIncremental(new ProjectCollectionContext(
                PROJECT_ID,
                Map.of(CollectionProvider.GITHUB, githubRequest,
                        CollectionProvider.JIRA, jiraRequest,
                        CollectionProvider.SLACK, slackRequest)
        ));

        // 해제된 Jira는 0건으로 기록되는 게 아니라 결과에서 아예 빠진다
        assertThat(result.published()).containsOnlyKeys(CollectionProvider.GITHUB, CollectionProvider.SLACK);
        assertThat(result.of(CollectionProvider.GITHUB)).isEqualTo(4);
        assertThat(result.of(CollectionProvider.SLACK)).isEqualTo(2);
        assertThat(result.total()).isEqualTo(6);
        verify(slack).collect(PROJECT_ID, slackRequest);
        assertThat(runContext.currentIntegrationId()).isEmpty();
    }

    @Test
    @DisplayName("해제된 provider를 건너뛰어도 다른 예외는 그대로 전파하고 이후 provider는 돌지 않는다")
    void collectIncremental_otherFailureAfterDisconnectedProvider_stillPropagates() {
        RawFetchRequest githubRequest = new RawFetchRequest("Bearer gh", "owner/repo", Map.of());
        RawFetchRequest jiraRequest = new RawFetchRequest("Bearer jira", "PLAT", Map.of());
        RawFetchRequest slackRequest = new RawFetchRequest("Bearer slack", null, Map.of());
        when(github.collect(PROJECT_ID, githubRequest))
                .thenThrow(new IntegrationDisconnectedException("github integration disconnected"));
        when(jira.collect(PROJECT_ID, jiraRequest)).thenThrow(new IllegalStateException("publish failed"));

        assertThatThrownBy(() -> pipelineService.collectIncremental(new ProjectCollectionContext(
                PROJECT_ID,
                Map.of(CollectionProvider.GITHUB, githubRequest,
                        CollectionProvider.JIRA, jiraRequest,
                        CollectionProvider.SLACK, slackRequest)
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish failed");
        verify(slack, never()).collect(anyString(), any());
    }

    private static SourceCollector collector(CollectionProvider provider) {
        SourceCollector collector = mock(SourceCollector.class);
        when(collector.provider()).thenReturn(provider);
        return collector;
    }
}
