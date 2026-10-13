package com.history.backend.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.plan.beta-reconcile", name = "enabled", havingValue = "true")
public class BetaIncrementalReconcileScheduler {

    private final BetaIncrementalReconcileService betaIncrementalReconcileService;

    // cron이 아니라 initialDelay=0 + fixedDelay: 배포 직후 기존 FREE 연동을 바로 켜고,
    // 베타 종료 뒤에도 최대 한 주기 안에 끄기 위해서다(cron은 다음 정각까지 방치한다).
    @Scheduled(initialDelay = 0, fixedDelayString = "${app.plan.beta-reconcile.interval}")
    public void reconcile() {
        try {
            betaIncrementalReconcileService.reconcile();
        } catch (RuntimeException exception) {
            // 실패 로그 기록 후 재던져 스케줄러 에러 처리에 위임
            log.error("Failed to reconcile incremental flags of free users.", exception);
            throw exception;
        }
    }
}
