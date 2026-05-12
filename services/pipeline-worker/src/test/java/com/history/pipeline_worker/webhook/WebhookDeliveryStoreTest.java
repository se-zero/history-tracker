package com.history.pipeline_worker.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookDeliveryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void tryClaim_duplicateDelivery_returnsFalse() {
        FileWebhookDeliveryStore store = new FileWebhookDeliveryStore(
                tempDir.resolve("deliveries.json").toString(),
                objectMapper()
        );

        assertThat(store.tryClaim("delivery-1")).isTrue();
        assertThat(store.tryClaim("delivery-1")).isFalse();
    }

    @Test
    void markFailed_allowsRetry() {
        FileWebhookDeliveryStore store = new FileWebhookDeliveryStore(
                tempDir.resolve("deliveries.json").toString(),
                objectMapper()
        );

        assertThat(store.tryClaim("delivery-1")).isTrue();
        store.markFailed("delivery-1");

        assertThat(store.tryClaim("delivery-1")).isTrue();
    }

    @Test
    void processedDelivery_survivesReload() {
        Path path = tempDir.resolve("deliveries.json");
        FileWebhookDeliveryStore store = new FileWebhookDeliveryStore(path.toString(), objectMapper());
        store.tryClaim("delivery-1");
        store.markProcessed("delivery-1");

        FileWebhookDeliveryStore reloaded = new FileWebhookDeliveryStore(path.toString(), objectMapper());

        assertThat(reloaded.tryClaim("delivery-1")).isFalse();
    }

    @Test
    void inProgressDelivery_isRetryableAfterReload() {
        Path path = tempDir.resolve("deliveries.json");
        FileWebhookDeliveryStore store = new FileWebhookDeliveryStore(path.toString(), objectMapper());
        store.tryClaim("delivery-1");

        FileWebhookDeliveryStore reloaded = new FileWebhookDeliveryStore(path.toString(), objectMapper());

        assertThat(reloaded.tryClaim("delivery-1")).isTrue();
    }

    @Test
    void inProgressDelivery_isRemovedFromFileAfterReload() {
        Path path = tempDir.resolve("deliveries.json");
        FileWebhookDeliveryStore store = new FileWebhookDeliveryStore(path.toString(), objectMapper());
        store.tryClaim("delivery-1");

        new FileWebhookDeliveryStore(path.toString(), objectMapper());
        FileWebhookDeliveryStore reloadedAgain = new FileWebhookDeliveryStore(path.toString(), objectMapper());

        assertThat(reloadedAgain.tryClaim("delivery-1")).isTrue();
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
