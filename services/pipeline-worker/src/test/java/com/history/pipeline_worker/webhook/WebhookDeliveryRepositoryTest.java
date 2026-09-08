package com.history.pipeline_worker.webhook;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliveryRepositoryTest {

    // claim 단위가 (delivery_id, project_id)로 바뀌었다는 계약을 SQL 문자열로 고정한다 — 팬아웃으로
    // 웹훅 하나를 여러 프로젝트가 각자 claim해야 하므로 delivery_id 단독 충돌 판정이면 안 된다.
    @Test
    void tryClaim_returnsTrueWhenInsertSucceeds() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(contains("ON CONFLICT (delivery_id, project_id) DO NOTHING"), any(MapSqlParameterSource.class)))
                .thenReturn(1);
        WebhookDeliveryRepository repository = new WebhookDeliveryRepository(jdbcTemplate);

        boolean claimed = repository.tryClaim("delivery-1", UUID.randomUUID());

        assertThat(claimed).isTrue();
    }

    @Test
    void tryClaim_returnsFalseWhenDeliveryAlreadyExists() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(contains("ON CONFLICT (delivery_id, project_id) DO NOTHING"), any(MapSqlParameterSource.class)))
                .thenReturn(0);
        WebhookDeliveryRepository repository = new WebhookDeliveryRepository(jdbcTemplate);

        boolean claimed = repository.tryClaim("delivery-1", UUID.randomUUID());

        assertThat(claimed).isFalse();
    }

    // markProcessed도 project_id로 좁혀 갱신한다 — 좁히지 않으면 같은 delivery_id를 claim한 다른
    // 프로젝트의 행까지 함께 PROCESSED로 바뀐다.
    @Test
    void markProcessed_scopesUpdateToProjectId() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        WebhookDeliveryRepository repository = new WebhookDeliveryRepository(jdbcTemplate);

        int updated = repository.markProcessed("delivery-1", UUID.randomUUID());

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue()).contains("project_id = :projectId");
    }

    // markFailed도 같은 이유로 project_id를 포함해야 한다 — 없으면 projectId 없이 지우거나 갱신하는
    // 구현에서도 이 테스트가 통과해버려 의미가 없다.
    @Test
    void markFailed_scopesUpdateToProjectId() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        WebhookDeliveryRepository repository = new WebhookDeliveryRepository(jdbcTemplate);

        int updated = repository.markFailed("delivery-1", UUID.randomUUID(), "boom");

        assertThat(updated).isEqualTo(1);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue()).contains("project_id = :projectId");
    }

    // releaseClaim이 projectId 없이 delivery_id만으로 지우면, 같은 delivery_id로 다른 프로젝트가
    // claim한 IN_PROGRESS 행까지 함께 해제해 그 프로젝트의 claim을 잃게 된다 — project_id 조건 필수.
    @Test
    void releaseClaim_deletesOnlyInProgressDeliveryScopedToProject() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        WebhookDeliveryRepository repository = new WebhookDeliveryRepository(jdbcTemplate);

        int deleted = repository.releaseClaim("delivery-1", UUID.randomUUID());

        assertThat(deleted).isEqualTo(1);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue()).contains("status = 'IN_PROGRESS'", "project_id = :projectId");
    }

    @Test
    void markStaleInProgressFailed_marksOldInProgressRowsFailed() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(contains("received_at < :staleBefore"), any(MapSqlParameterSource.class)))
                .thenReturn(2);
        WebhookDeliveryRepository repository = new WebhookDeliveryRepository(jdbcTemplate);

        int updated = repository.markStaleInProgressFailed(
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                "stale"
        );

        assertThat(updated).isEqualTo(2);
    }
}
