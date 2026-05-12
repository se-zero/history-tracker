package com.history.pipeline_worker.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.webhook.delivery-store.type", havingValue = "file", matchIfMissing = true)
public class FileWebhookDeliveryStore implements WebhookDeliveryStore {

    private final Path path;
    private final ObjectMapper objectMapper;
    private final Object lock = new Object();
    private DeliveryData cached;

    public FileWebhookDeliveryStore(
            @Value("${app.webhook.delivery-store.path:./webhook-deliveries.json}") String path,
            ObjectMapper objectMapper
    ) {
        this.path = Path.of(path);
        this.objectMapper = objectMapper;
        this.cached = load();
    }

    @Override
    public boolean tryClaim(String deliveryId) {
        synchronized (lock) {
            if (cached.deliveries.containsKey(deliveryId)) {
                return false;
            }
            cached.deliveries.put(deliveryId, DeliveryRecord.inProgress());
            save(cached);
            return true;
        }
    }

    @Override
    public void markProcessed(String deliveryId) {
        synchronized (lock) {
            cached.deliveries.put(deliveryId, DeliveryRecord.processed());
            save(cached);
        }
    }

    @Override
    public void markFailed(String deliveryId) {
        synchronized (lock) {
            cached.deliveries.remove(deliveryId);
            save(cached);
        }
    }

    private DeliveryData load() {
        if (!Files.exists(path)) {
            return new DeliveryData();
        }
        try {
            DeliveryData data = objectMapper.readValue(path.toFile(), DeliveryData.class);
            if (data.deliveries == null) {
                data.deliveries = new HashMap<>();
            }
            int removed = removeInProgressDeliveries(data);
            if (removed > 0) {
                log.warn("Removed {} stale IN_PROGRESS webhook deliveries during startup", removed);
                save(data);
            }
            return data;
        } catch (IOException e) {
            log.warn("Webhook delivery store read failed, starting empty: {}", e.getMessage());
            return new DeliveryData();
        }
    }

    private int removeInProgressDeliveries(DeliveryData data) {
        int before = data.deliveries.size();
        data.deliveries.entrySet().removeIf(entry ->
                DeliveryRecord.IN_PROGRESS.equals(entry.getValue().status)
        );
        return before - data.deliveries.size();
    }

    private void save(DeliveryData data) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save webhook delivery store", e);
        }
    }

    public static class DeliveryData {
        public Map<String, DeliveryRecord> deliveries = new HashMap<>();
    }

    public static class DeliveryRecord {
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String PROCESSED = "PROCESSED";

        public String status;
        public Instant updatedAt;

        public static DeliveryRecord inProgress() {
            return of(IN_PROGRESS);
        }

        public static DeliveryRecord processed() {
            return of(PROCESSED);
        }

        private static DeliveryRecord of(String status) {
            DeliveryRecord record = new DeliveryRecord();
            record.status = status;
            record.updatedAt = Instant.now();
            return record;
        }
    }
}
