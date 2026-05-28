package com.history.pipeline_worker.checkpoint;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final CheckpointRepository repository = mock(CheckpointRepository.class);
    private final CheckpointService service = new CheckpointService(repository);

    @Test
    void loadProjectCheckpoints_mapsRowsToSnapshot() {
        when(repository.findAllByProjectId(PROJECT_ID)).thenReturn(List.of(
                row("github", "github_commits", "2024-01-01T00:00:00Z"),
                row("github", "github_pull_requests", "2024-01-02T00:00:00Z"),
                row("github", "github_issues", "2024-01-03T00:00:00Z"),
                row("jira", "jira_updated", "2024-02-01T00:00:00Z"),
                row("slack", "slack_messages", "2024-03-01T00:00:00Z")
        ));

        ProjectCheckpointData result = service.loadProjectCheckpoints(PROJECT_ID.toString());

        assertThat(result.github.commitsScannedAt).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"));
        assertThat(result.github.pullRequestsScannedAt).isEqualTo(Instant.parse("2024-01-02T00:00:00Z"));
        assertThat(result.github.issuesScannedAt).isEqualTo(Instant.parse("2024-01-03T00:00:00Z"));
        assertThat(result.jira.lastScannedAt).isEqualTo(Instant.parse("2024-02-01T00:00:00Z"));
        assertThat(result.slack.lastScannedAt).isEqualTo(Instant.parse("2024-03-01T00:00:00Z"));
    }

    @Test
    void updateGitHubCommits_usesExpectedCursorKey() {
        Instant cursor = Instant.parse("2024-01-01T00:00:00Z");

        service.updateGitHubCommits(PROJECT_ID.toString(), cursor);

        verify(repository).upsertCursor(PROJECT_ID, "github", "github_commits", cursor);
    }

    @Test
    void updateJira_usesExpectedCursorKey() {
        Instant cursor = Instant.parse("2024-02-01T00:00:00Z");

        service.updateJira(PROJECT_ID.toString(), cursor);

        verify(repository).upsertCursor(PROJECT_ID, "jira", "jira_updated", cursor);
    }

    @Test
    void updateSlack_usesExpectedCursorKey() {
        Instant cursor = Instant.parse("2024-03-01T00:00:00Z");

        service.updateSlack(PROJECT_ID.toString(), cursor);

        verify(repository).upsertCursor(PROJECT_ID, "slack", "slack_messages", cursor);
    }

    private CheckpointRepository.CheckpointRow row(String provider, String cursorKey, String cursorValue) {
        Instant cursor = Instant.parse(cursorValue);
        return new CheckpointRepository.CheckpointRow(PROJECT_ID, provider, cursorKey, cursor, cursor);
    }
}
