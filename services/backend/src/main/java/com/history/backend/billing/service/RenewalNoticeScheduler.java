package com.history.backend.billing.service;

import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.billing.renewal-notice", name = "enabled", havingValue = "true")
public class RenewalNoticeScheduler {

    private final RenewalNoticeService renewalNoticeService;

    @Scheduled(cron = "${app.billing.renewal-notice.cron}")
    public void sendDueNotices() {
        try {
            int sent = renewalNoticeService.sendDueNotices(Instant.now());
            log.info("Sent billing renewal notices. count={}", sent);
        } catch (RuntimeException exception) {
            log.error("Failed to send billing renewal notices.", exception);
            throw exception;
        }
    }
}
