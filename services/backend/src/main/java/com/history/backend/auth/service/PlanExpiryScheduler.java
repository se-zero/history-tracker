package com.history.backend.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.plan.expiry", name = "enabled", havingValue = "true")
public class PlanExpiryScheduler {

    private final PlanExpiryService planExpiryService;

    @Scheduled(cron = "${app.plan.expiry.cron}")
    public void downgradeExpiredPlans() {
        try {
            int downgradedCount = planExpiryService.downgradeExpiredPlans();
            log.info("Downgraded expired paid plans. count={}", downgradedCount);
        } catch (RuntimeException exception) {
            // 실패 로그 기록 후 재던져 스케줄러 에러 처리에 위임
            log.error("Failed to downgrade expired paid plans.", exception);
            throw exception;
        }
    }
}
