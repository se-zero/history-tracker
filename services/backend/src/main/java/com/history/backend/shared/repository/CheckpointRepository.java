package com.history.backend.shared.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.history.backend.integration.domain.IntegrationProvider;
import com.history.backend.shared.domain.Checkpoint;
import com.history.backend.shared.domain.CheckpointId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckpointRepository extends JpaRepository<Checkpoint, CheckpointId> {

    List<Checkpoint> findAllByProject_Id(UUID projectId);

    List<Checkpoint> findAllByProject_IdAndId_Provider(UUID projectId, IntegrationProvider provider);

    @Modifying
    @Query(
            value = """
                    INSERT INTO checkpoints (project_id, provider, cursor_key, cursor_value, updated_at)
                    VALUES (:projectId, :provider, :cursorKey, :cursorValue, :updatedAt)
                    ON CONFLICT (project_id, provider, cursor_key)
                    DO UPDATE SET cursor_value = GREATEST(checkpoints.cursor_value, EXCLUDED.cursor_value),
                                  updated_at = CASE
                                      WHEN EXCLUDED.cursor_value > checkpoints.cursor_value
                                      THEN EXCLUDED.updated_at
                                      ELSE checkpoints.updated_at
                                  END
                    """,
            nativeQuery = true
    )
    int upsertCursorValue(
            @Param("projectId") UUID projectId,
            @Param("provider") String provider,
            @Param("cursorKey") String cursorKey,
            @Param("cursorValue") Instant cursorValue,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * Upserts a cursor value while preserving monotonic progress.
     *
     * <p>On conflict, older cursor timestamps are ignored so a delayed collection
     * result cannot move a checkpoint backward.</p>
     */
    default void upsertCursor(
            UUID projectId,
            IntegrationProvider provider,
            String cursorKey,
            Instant cursorValue
    ) {
        upsertCursorValue(projectId, provider.value(), cursorKey, cursorValue, Instant.now());
    }
}
