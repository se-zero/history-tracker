package com.history.pipeline_worker.webhook;

public interface WebhookDeliveryStore {

    boolean tryClaim(String deliveryId);

    void markProcessed(String deliveryId);

    void markFailed(String deliveryId);
}
