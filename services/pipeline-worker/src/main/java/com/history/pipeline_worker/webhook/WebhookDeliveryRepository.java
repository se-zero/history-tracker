package com.history.pipeline_worker.webhook;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Repository
public class WebhookDeliveryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public WebhookDeliveryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryClaim(String deliveryId, UUID projectId) {
        String sql = """
                INSERT INTO webhook_deliveries (
                    delivery_id, project_id, status, received_at, updated_at
                )
                VALUES (:deliveryId, :projectId, 'IN_PROGRESS', :now, :now)
                ON CONFLICT (delivery_id) DO NOTHING
                """;

        int inserted = jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("deliveryId", deliveryId)
                .addValue("projectId", projectId)
                .addValue("now", OffsetDateTime.now(ZoneOffset.UTC)));
        return inserted == 1;
    }

    public int markProcessed(String deliveryId) {
        String sql = """
                UPDATE webhook_deliveries
                SET status = 'PROCESSED',
                    updated_at = :now,
                    last_error = NULL
                WHERE delivery_id = :deliveryId
                """;

        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("deliveryId", deliveryId)
                .addValue("now", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    public int markFailed(String deliveryId, String lastError) {
        String sql = """
                UPDATE webhook_deliveries
                SET status = 'FAILED',
                    updated_at = :now,
                    last_error = :lastError
                WHERE delivery_id = :deliveryId
                """;

        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("deliveryId", deliveryId)
                .addValue("lastError", lastError)
                .addValue("now", OffsetDateTime.now(ZoneOffset.UTC)));
    }

    public int releaseClaim(String deliveryId) {
        String sql = """
                DELETE FROM webhook_deliveries
                WHERE delivery_id = :deliveryId
                  AND status = 'IN_PROGRESS'
                """;

        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("deliveryId", deliveryId));
    }

    public int markStaleInProgressFailed(Instant staleBefore, String lastError) {
        String sql = """
                UPDATE webhook_deliveries
                SET status = 'FAILED',
                    updated_at = :now,
                    last_error = :lastError
                WHERE status = 'IN_PROGRESS'
                  AND received_at < :staleBefore
                """;

        return jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("staleBefore", OffsetDateTime.ofInstant(staleBefore, ZoneOffset.UTC))
                .addValue("lastError", lastError)
                .addValue("now", OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
