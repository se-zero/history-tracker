package com.history.backend.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.billing.subscription-sync", name = "enabled", havingValue = "true")
public class PaddleSubscriptionSyncScheduler {

    private final PaddleSubscriptionSyncService syncService;

    @Scheduled(cron = "${app.billing.subscription-sync.cron}")
    public void run() {
        try {
            syncService.syncAll();
        } catch (RuntimeException exception) {
            log.error("Failed to sync Paddle subscriptions.", exception);
            throw exception;
        }
    }
}
