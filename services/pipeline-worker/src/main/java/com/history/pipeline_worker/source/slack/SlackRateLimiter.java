package com.history.pipeline_worker.source.slack;

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

    /** conversations.list 호출 후 대기 (Tier 2: 20/min). 429로 승격된 minDelayMs가 설정값보다 크면 그만큼 대기한다. */
    public void afterConversationsList(long minDelayMs) {
        sleep(Math.max(listDelayMs, minDelayMs));
    }

    /** users.list 호출 후 대기. 429로 승격된 minDelayMs가 설정값보다 크면 그만큼 대기한다. */
    public void afterUsersList(long minDelayMs) {
        sleep(Math.max(listDelayMs, minDelayMs));
    }

    /** conversations.history 호출 후 대기 (Tier 3: 50/min). 429로 승격된 minDelayMs가 설정값보다 크면 그만큼 대기한다. */
    public void afterConversationsHistory(long minDelayMs) {
        sleep(Math.max(historyDelayMs, minDelayMs));
    }

    /** conversations.replies 호출 후 대기 (Tier 3: 50/min). 429로 승격된 minDelayMs가 설정값보다 크면 그만큼 대기한다. */
    public void afterConversationsReplies(long minDelayMs) {
        sleep(Math.max(repliesDelayMs, minDelayMs));
    }

    /** 429 Retry-After(초)만큼 대기 */
    public void awaitRetry(long retryAfterSeconds) {
        sleep(retryAfterSeconds * 1000);
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
