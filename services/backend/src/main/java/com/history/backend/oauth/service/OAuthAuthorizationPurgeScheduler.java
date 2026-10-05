package com.history.backend.oauth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "user-lifecycle.purge", name = "enabled", havingValue = "true")
public class OAuthAuthorizationPurgeScheduler {

    private final OAuthGrantService oAuthGrantService;

    @Scheduled(cron = "${user-lifecycle.purge.cron}")
    public void purgeExpiredAuthorizations() {
        try {
            int purgedCount = oAuthGrantService.purgeExpired();
            log.info("Purged expired OAuth authorizations. count={}", purgedCount);
            // 연결 정리 뒤에 해야 한다 — 뒤집으면 방금 만료된 연결의 앱이 하루 더 남는다
            int purgedClients = oAuthGrantService.purgeUnusedClients();
            log.info("Purged unused OAuth clients. count={}", purgedClients);
        } catch (RuntimeException exception) {
            log.error("Failed to purge expired OAuth authorizations or unused clients.", exception);
            throw exception;
        }
    }
}
