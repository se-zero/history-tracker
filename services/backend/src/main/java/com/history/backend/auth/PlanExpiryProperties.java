package com.history.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.plan.expiry")
public record PlanExpiryProperties(
        boolean enabled,
        String cron,
        int batchSize
) {

    public PlanExpiryProperties {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("app.plan.expiry.cron must not be blank.");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.plan.expiry.batch-size must be positive.");
        }
    }
}
