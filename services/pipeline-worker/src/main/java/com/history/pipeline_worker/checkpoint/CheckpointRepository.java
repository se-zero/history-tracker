package com.history.pipeline_worker.checkpoint;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class CheckpointRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CheckpointRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CheckpointRow> findAllByProjectId(UUID projectId) {
        String sql = """
                SELECT project_id, provider, cursor_key, cursor_value, updated_at
                FROM checkpoints
                WHERE project_id = :projectId
                ORDER BY provider, cursor_key
                """;

        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource("projectId", projectId),
                checkpointRowMapper()
        );
    }

    public int upsertCursor(UUID projectId, String provider, String cursorKey, Instant cursorValue) {
        String sql = """
                INSERT INTO checkpoints (project_id, provider, cursor_key, cursor_value, updated_at)
                VALUES (:projectId, :provider, :cursorKey, :cursorValue, :updatedAt)
                ON CONFLICT (project_id, provider, cursor_key)
                DO UPDATE SET cursor_value = GREATEST(checkpoints.cursor_value, EXCLUDED.cursor_value),
                              updated_at = CASE
                                  WHEN EXCLUDED.cursor_value > checkpoints.cursor_value
                                  THEN EXCLUDED.updated_at
                                  ELSE checkpoints.updated_at
                              END
                """;

        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("provider", provider)
                .addValue("cursorKey", cursorKey)
                .addValue("cursorValue", cursorValue)
                .addValue("updatedAt", Instant.now()));
    }

    private RowMapper<CheckpointRow> checkpointRowMapper() {
        return (rs, rowNum) -> new CheckpointRow(
                rs.getObject("project_id", UUID.class),
                rs.getString("provider"),
                rs.getString("cursor_key"),
                rs.getTimestamp("cursor_value").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    public record CheckpointRow(
            UUID projectId,
            String provider,
            String cursorKey,
            Instant cursorValue,
            Instant updatedAt
    ) {
    }
}
