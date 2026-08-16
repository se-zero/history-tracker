package com.history.pipeline_worker.checkpoint;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointRepositoryTest {

    @Test
    void upsertCursor_usesMonotonicProgressSql() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(contains("GREATEST(checkpoints.cursor_value, EXCLUDED.cursor_value)"),
                any(MapSqlParameterSource.class))).thenReturn(1);
        CheckpointRepository repository = new CheckpointRepository(jdbcTemplate);

        int updated = repository.upsertCursor(
                UUID.randomUUID(),
                "github",
                "github_commits",
                Instant.parse("2026-01-01T00:00:00Z")
        );

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void deleteCursor_issuesDeleteByFullKey() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        CheckpointRepository repository = new CheckpointRepository(jdbcTemplate);
        UUID projectId = UUID.randomUUID();

        int deleted = repository.deleteCursor(projectId, "slack", "slack_messages:C123");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), paramsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertThat(sql).contains("DELETE FROM checkpoints");
        assertThat(sql).contains("project_id = :projectId");
        assertThat(sql).contains("provider = :provider");
        assertThat(sql).contains("cursor_key = :cursorKey");

        MapSqlParameterSource params = paramsCaptor.getValue();
        assertThat(params.getValue("projectId")).isEqualTo(projectId);
        assertThat(params.getValue("provider")).isEqualTo("slack");
        assertThat(params.getValue("cursorKey")).isEqualTo("slack_messages:C123");

        assertThat(deleted).isEqualTo(1);
    }
}
