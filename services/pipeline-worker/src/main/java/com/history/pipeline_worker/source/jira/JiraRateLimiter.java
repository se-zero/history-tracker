package com.history.pipeline_worker.source.jira;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JiraRateLimiter {

    private final long delayMs;

    public JiraRateLimiter(@Value("${app.rate-limit.jira.delay-ms:200}") long delayMs) {
        this.delayMs = delayMs;
    }

    /** Jira API 호출 후 대기 (권장: 초당 10회 이하) */
    public void acquire() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("JiraRateLimiter sleep 중단", e);
        }
    }
}
