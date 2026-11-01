package com.history.pipeline_worker.checkpoint;

import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.CollectionRunContext;
import com.history.pipeline_worker.collection.IntegrationDisconnectedException;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class CheckpointServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INTEGRATION_ID = UUID.fromString("a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1");

    private final CheckpointRepository repository = mock(CheckpointRepository.class);
    // CollectionRunContext는 실제 객체 — 연동 행이 남아 있는지만 integrationRepository Mock으로 바꾼다
    private final ProjectIntegrationRepository integrationRepository = mock(ProjectIntegrationRepository.class);
    private final CollectionRunContext runContext = new CollectionRunContext(integrationRepository);
    private final CheckpointService service = new CheckpointService(repository, runContext);

    @Test
    void loadCursors_returnsProviderCursorsByKey() {
        when(repository.findAllByProjectIdAndProvider(PROJECT_ID, "github")).thenReturn(List.of(
                row("github", "github_commits", "2024-01-01T00:00:00Z"),
                row("github", "github_pull_requests", "2024-01-02T00:00:00Z"),
                row("github", "github_issues", "2024-01-03T00:00:00Z")
        ));

        Map<String, Instant> cursors = service.loadCursors(PROJECT_ID.toString(), CollectionProvider.GITHUB);

        assertThat(cursors).containsOnly(
                Map.entry("github_commits", Instant.parse("2024-01-01T00:00:00Z")),
                Map.entry("github_pull_requests", Instant.parse("2024-01-02T00:00:00Z")),
                Map.entry("github_issues", Instant.parse("2024-01-03T00:00:00Z"))
        );
    }

    @Test
    void loadCursors_withoutRows_returnsEmptyMap() {
        when(repository.findAllByProjectIdAndProvider(PROJECT_ID, "jira")).thenReturn(List.of());

        assertThat(service.loadCursors(PROJECT_ID.toString(), CollectionProvider.JIRA)).isEmpty();
    }

    @Test
    void updateCursor_delegatesProviderValueAndKey() {
        Instant cursor = Instant.parse("2024-02-01T00:00:00Z");

        service.updateCursor(PROJECT_ID.toString(), CollectionProvider.JIRA, "jira_updated", cursor);

        verify(repository).upsertCursor(PROJECT_ID, "jira", "jira_updated", cursor);
    }

    @Test
    void updateCursor_nullValue_doesNotTouchRepository() {
        service.updateCursor(PROJECT_ID.toString(), CollectionProvider.SLACK, "slack_messages", null);

        verify(repository, never()).upsertCursor(any(), anyString(), anyString(), any());
    }

    @Test
    void deleteCursor_delegatesProviderValueAndKey() {
        service.deleteCursor(PROJECT_ID.toString(), CollectionProvider.SLACK, "slack_messages:C123");

        verify(repository).deleteCursor(PROJECT_ID, "slack", "slack_messages:C123");
    }

    // 해제 때 지운 커서를 끝에서 되살리면 재연결 뒤 새 연동이 옛 커서부터 이어받아 구간을 놓친다.
    @Test
    @DisplayName("범위가 열린 연동 행이 없으면 updateCursor는 예외를 던지고 커서를 쓰지 않는다")
    void updateCursor_integrationDisconnected_throwsWithoutWriting() {
        when(integrationRepository.existsById(INTEGRATION_ID)).thenReturn(false);

        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID.toString())) {
            assertThatThrownBy(() -> service.updateCursor(
                    PROJECT_ID.toString(), CollectionProvider.JIRA, "jira_updated", Instant.parse("2024-02-01T00:00:00Z")))
                    .isInstanceOf(IntegrationDisconnectedException.class);
        }

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("범위가 열린 연동 행이 없으면 deleteCursor는 예외를 던지고 커서를 지우지 않는다")
    void deleteCursor_integrationDisconnected_throwsWithoutDeleting() {
        when(integrationRepository.existsById(INTEGRATION_ID)).thenReturn(false);

        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID.toString())) {
            assertThatThrownBy(() -> service.deleteCursor(
                    PROJECT_ID.toString(), CollectionProvider.SLACK, "slack_messages:C123"))
                    .isInstanceOf(IntegrationDisconnectedException.class);
        }

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("연동 행이 남아 있으면 범위가 열려 있어도 updateCursor·deleteCursor는 그대로 동작한다")
    void cursorWrites_integrationActive_delegateToRepository() {
        when(integrationRepository.existsById(INTEGRATION_ID)).thenReturn(true);
        Instant cursor = Instant.parse("2024-02-01T00:00:00Z");

        try (CollectionRunContext.Scope scope = runContext.open(INTEGRATION_ID.toString())) {
            service.updateCursor(PROJECT_ID.toString(), CollectionProvider.JIRA, "jira_updated", cursor);
            service.deleteCursor(PROJECT_ID.toString(), CollectionProvider.SLACK, "slack_messages:C123");
        }

        verify(repository).upsertCursor(PROJECT_ID, "jira", "jira_updated", cursor);
        verify(repository).deleteCursor(PROJECT_ID, "slack", "slack_messages:C123");
    }

    private CheckpointRepository.CheckpointRow row(String provider, String cursorKey, String cursorValue) {
        Instant cursor = Instant.parse(cursorValue);
        return new CheckpointRepository.CheckpointRow(PROJECT_ID, provider, cursorKey, cursor, cursor);
    }
}
