package com.history.pipeline_worker.checkpoint;

import com.history.pipeline_worker.collection.CollectionProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class CheckpointServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final CheckpointRepository repository = mock(CheckpointRepository.class);
    private final CheckpointService service = new CheckpointService(repository);

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

    private CheckpointRepository.CheckpointRow row(String provider, String cursorKey, String cursorValue) {
        Instant cursor = Instant.parse(cursorValue);
        return new CheckpointRepository.CheckpointRow(PROJECT_ID, provider, cursorKey, cursor, cursor);
    }
}
