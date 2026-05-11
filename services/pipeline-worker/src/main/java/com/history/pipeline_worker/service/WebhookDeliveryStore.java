package com.history.pipeline_worker.service;

public interface WebhookDeliveryStore {

    boolean tryClaim(String deliveryId);

    void markProcessed(String deliveryId);

    void markFailed(String deliveryId);
}
