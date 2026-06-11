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
public class UserPurgeScheduler {

    private final UserPurgeService userPurgeService;

    @Scheduled(cron = "${user-lifecycle.purge.cron}")
    public void purgeExpiredUsers() {
        try {
            int purgedCount = userPurgeService.purgeExpiredUsers();
            log.info("Purged expired deactivated users. count={}", purgedCount);
        } catch (RuntimeException exception) {
            // 실패 로그 기록 후 재던져 스케줄러 에러 처리에 위임
            log.error("Failed to purge expired deactivated users.", exception);
            throw exception;
        }
    }
}
