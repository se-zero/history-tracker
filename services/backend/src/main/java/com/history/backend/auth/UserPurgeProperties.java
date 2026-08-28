package com.history.backend.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "user-lifecycle.purge")
public record UserPurgeProperties(
        boolean enabled,
        Duration gracePeriod,
        String cron,
        int batchSize,
        Duration forcePurgeAfter
) {

    public UserPurgeProperties {
        if (gracePeriod == null || gracePeriod.isNegative() || gracePeriod.isZero()) {
            throw new IllegalArgumentException("user-lifecycle.purge.grace-period must be positive.");
        }
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("user-lifecycle.purge.cron must not be blank.");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("user-lifecycle.purge.batch-size must be positive.");
        }
        if (forcePurgeAfter == null || forcePurgeAfter.isNegative() || forcePurgeAfter.isZero()) {
            throw new IllegalArgumentException("user-lifecycle.purge.force-purge-after must be positive.");
        }
    }
}
