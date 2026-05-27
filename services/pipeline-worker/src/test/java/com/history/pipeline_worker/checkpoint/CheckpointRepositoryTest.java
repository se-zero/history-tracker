package com.history.pipeline_worker.checkpoint;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
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
}
