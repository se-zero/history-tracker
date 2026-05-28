package com.history.pipeline_worker.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class WebhookDeliveryService {

    private final WebhookDeliveryRepository repository;
    private final Duration staleInProgressTimeout;

    public WebhookDeliveryService(
            WebhookDeliveryRepository repository,
            @Value("${app.webhook.delivery.stale-in-progress-timeout:10m}") Duration staleInProgressTimeout
    ) {
        this.repository = repository;
        this.staleInProgressTimeout = staleInProgressTimeout;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markStaleInProgressFailed() {
        Instant staleBefore = Instant.now().minus(staleInProgressTimeout);
        try {
            int updated = repository.markStaleInProgressFailed(
                    staleBefore,
                    "Worker restarted before collection completed."
            );
            if (updated > 0) {
                log.warn("Marked {} stale IN_PROGRESS webhook deliveries as FAILED.", updated);
            }
        } catch (DataAccessException exception) {
            log.warn("Failed to mark stale IN_PROGRESS webhook deliveries.", exception);
        }
    }

    public boolean tryClaim(String deliveryId, String projectId) {
        return repository.tryClaim(deliveryId, UUID.fromString(projectId));
    }

    public void markProcessed(String deliveryId) {
        int updated = repository.markProcessed(deliveryId);
        if (updated == 0) {
            log.warn("No webhook delivery was marked PROCESSED: deliveryId={}", deliveryId);
        }
    }

    public void markFailed(String deliveryId, String lastError) {
        int updated = repository.markFailed(deliveryId, lastError);
        if (updated == 0) {
            log.warn("No webhook delivery was marked FAILED: deliveryId={}", deliveryId);
        }
    }

    public void releaseClaim(String deliveryId) {
        int deleted = repository.releaseClaim(deliveryId);
        if (deleted == 0) {
            log.warn("No IN_PROGRESS webhook delivery claim was released: deliveryId={}", deliveryId);
        }
    }
}
