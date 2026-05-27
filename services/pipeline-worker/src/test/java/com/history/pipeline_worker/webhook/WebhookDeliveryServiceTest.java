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

    @Test
    void markFailed_preservesLastError() {
        service.markFailed("delivery-1", "collection failed");

        verify(repository).markFailed("delivery-1", "collection failed");
    }

    @Test
    void releaseClaim_deletesInProgressClaim() {
        service.releaseClaim("delivery-1");

        verify(repository).releaseClaim("delivery-1");
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
