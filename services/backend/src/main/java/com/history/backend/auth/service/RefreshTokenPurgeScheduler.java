package com.history.backend.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "user-lifecycle.purge", name = "enabled", havingValue = "true")
public class RefreshTokenPurgeScheduler {

    private final RefreshTokenService refreshTokenService;

    @Scheduled(cron = "${user-lifecycle.purge.cron}")
    public void purgeExpiredRefreshTokens() {
        try {
            int purgedCount = refreshTokenService.purgeExpiredRefreshTokens();
            log.info("Purged expired refresh tokens. count={}", purgedCount);
        } catch (RuntimeException exception) {
            log.error("Failed to purge expired refresh tokens.", exception);
            throw exception;
        }
    }
}
