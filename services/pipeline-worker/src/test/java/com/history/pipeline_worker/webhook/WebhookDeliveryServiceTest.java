package com.history.pipeline_worker.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliveryServiceTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";

    private final WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
    private final WebhookDeliveryService service = new WebhookDeliveryService(repository, Duration.ofMinutes(10));

    @Test
    void tryClaim_delegatesToRepositoryWithUuidProjectId() {
        UUID projectId = UUID.fromString(PROJECT_ID);
        when(repository.tryClaim("delivery-1", projectId)).thenReturn(true);

        service.tryClaim("delivery-1", PROJECT_ID);

        verify(repository).tryClaim("delivery-1", projectId);
    }

    @Test
    void tryClaim_rejectsInvalidProjectId() {
        assertThatThrownBy(() -> service.tryClaim("delivery-1", "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // claim 단위가 (delivery_id, project_id)로 바뀌면서 markProcessed도 projectId를 UUID로 변환해
    // 리포지토리에 넘겨야 한다 — 넘기지 않으면 다른 프로젝트의 같은 delivery_id 행까지 갱신된다.
    @Test
    void markProcessed_delegatesToRepositoryWithUuidProjectId() {
        UUID projectId = UUID.fromString(PROJECT_ID);

        service.markProcessed("delivery-1", PROJECT_ID);

        verify(repository).markProcessed("delivery-1", projectId);
    }

    @Test
    void markFailed_preservesLastErrorAndDelegatesWithUuidProjectId() {
        UUID projectId = UUID.fromString(PROJECT_ID);

        service.markFailed("delivery-1", PROJECT_ID, "collection failed");

        verify(repository).markFailed("delivery-1", projectId, "collection failed");
    }

    @Test
    void releaseClaim_delegatesToRepositoryWithUuidProjectId() {
        UUID projectId = UUID.fromString(PROJECT_ID);

        service.releaseClaim("delivery-1", PROJECT_ID);

        verify(repository).releaseClaim("delivery-1", projectId);
    }

    @Test
    void markStaleInProgressFailed_usesConfiguredTimeout() {
        service.markStaleInProgressFailed();

        verify(repository).markStaleInProgressFailed(
                org.mockito.ArgumentMatchers.argThat(staleBefore ->
                        staleBefore.isBefore(Instant.now()) && staleBefore.isAfter(Instant.now().minus(Duration.ofMinutes(11)))
                ),
                org.mockito.ArgumentMatchers.eq("Worker restarted before collection completed.")
        );
    }

    @Test
    void markStaleInProgressFailed_doesNotFailStartupWhenDatabaseIsUnavailable() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db unavailable"))
                .when(repository)
                .markStaleInProgressFailed(
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.anyString()
                );

        service.markStaleInProgressFailed();
    }
}
