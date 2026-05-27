package com.history.pipeline_worker.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookDeliveryRepositoryTest {

    @Test
    void tryClaim_returnsTrueWhenInsertSucceeds() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(contains("ON CONFLICT (delivery_id) DO NOTHING"), any(MapSqlParameterSource.class)))
                .thenReturn(1);
        WebhookDeliveryRepository repository = new WebhookDeliveryRepository(jdbcTemplate);

        boolean claimed = repository.tryClaim("delivery-1", UUID.randomUUID());

        assertThat(claimed).isTrue();
    }

    @Test
    void tryClaim_returnsFalseWhenDeliveryAlreadyExists() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.update(contains("ON CONFLICT (delivery_id) DO NOTHING"), any(MapSqlParameterSource.class)))
                .thenReturn(0);
        WebhookDeliveryRepository repository = new WebhookDeliveryRepository(jdbcTemplate);

        boolean claimed = repository.tryClaim("delivery-1", UUID.randomUUID());

        assertThat(claimed).isFalse();
    }
}
