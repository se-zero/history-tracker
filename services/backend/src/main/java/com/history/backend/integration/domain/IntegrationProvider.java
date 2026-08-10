package com.history.backend.integration.domain;

import java.util.Arrays;

public enum IntegrationProvider {
    GITHUB("github", "GitHub"),
    SLACK("slack", "Slack"),
    JIRA("jira", "Jira"),
    DISCORD("discord", "Discord"),
    GOOGLE_CHAT("google-chat", "Google Chat");

    private final String value;
    private final String displayName;

    IntegrationProvider(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    public String value() {
        return value;
    }

    public String displayName() {
        return displayName;
    }

    public static IntegrationProvider fromValue(String value) {
        return Arrays.stream(values())
                .filter(provider -> provider.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported integration provider: " + value));
    }
}
