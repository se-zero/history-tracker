package com.history.backend.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conversation.memory")
public record ChatMemoryProperties(
        int historyBudgetChars,
        int summaryTriggerChars,
        int summaryFailureThreshold,
        int summarySkipTurns
) {

    public ChatMemoryProperties {
        if (historyBudgetChars < 1) {
            throw new IllegalArgumentException("conversation.memory.history-budget-chars must be positive.");
        }
        if (summaryTriggerChars < 1) {
            throw new IllegalArgumentException("conversation.memory.summary-trigger-chars must be positive.");
        }
        if (summaryFailureThreshold < 1) {
            throw new IllegalArgumentException("conversation.memory.summary-failure-threshold must be positive.");
        }
        if (summarySkipTurns < 1) {
            throw new IllegalArgumentException("conversation.memory.summary-skip-turns must be positive.");
        }
    }
}
