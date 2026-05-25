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
                    DO UPDATE SET cursor_value = EXCLUDED.cursor_value,
                                  updated_at = EXCLUDED.updated_at
                    """,
            nativeQuery = true
    )
    int upsertCursorValue(
            @Param("projectId") UUID projectId,
            @Param("provider") String provider,
            @Param("cursorKey") String cursorKey,
            @Param("cursorValue") String cursorValue,
            @Param("updatedAt") Instant updatedAt
    );

    /**
     * Upserts a cursor value without monotonicity guarantees.
     *
     * <p>The cursor value is provider-specific free-form text, so this repository
     * cannot safely compare old and new cursor values. Callers must serialize
     * updates for the same {@code (projectId, provider, cursorKey)} or otherwise
     * ensure an older collection result cannot overwrite a newer cursor.</p>
     */
    default void upsertCursor(
            UUID projectId,
            IntegrationProvider provider,
            String cursorKey,
            String cursorValue
    ) {
        upsertCursorValue(projectId, provider.value(), cursorKey, cursorValue, Instant.now());
    }
}
