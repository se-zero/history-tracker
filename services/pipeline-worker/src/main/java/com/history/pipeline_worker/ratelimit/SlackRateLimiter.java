package com.history.pipeline_worker.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SlackRateLimiter {

    private final long listDelayMs;
    private final long historyDelayMs;
    private final long repliesDelayMs;

    public SlackRateLimiter(
            @Value("${app.rate-limit.slack.conversations-list-delay-ms:3000}") long listDelayMs,
            @Value("${app.rate-limit.slack.conversations-history-delay-ms:1200}") long historyDelayMs,
            @Value("${app.rate-limit.slack.conversations-replies-delay-ms:1200}") long repliesDelayMs) {
        this.listDelayMs = listDelayMs;
        this.historyDelayMs = historyDelayMs;
        this.repliesDelayMs = repliesDelayMs;
    }

    /** conversations.list 호출 후 대기 (Tier 2: 20/min) */
    public void afterConversationsList() {
        sleep(listDelayMs);
    }

    /** conversations.history 호출 후 대기 (Tier 3: 50/min) */
    public void afterConversationsHistory() {
        sleep(historyDelayMs);
    }

    /** conversations.replies 호출 후 대기 (Tier 3: 50/min) */
    public void afterConversationsReplies() {
        sleep(repliesDelayMs);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("SlackRateLimiter sleep 중단", e);
        }
    }
}
