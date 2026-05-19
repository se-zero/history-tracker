package com.history.backend.integration.domain;

import java.util.Arrays;

public enum IntegrationProvider {
    GITHUB("github"),
    SLACK("slack"),
    JIRA("jira");

    private final String value;

    IntegrationProvider(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static IntegrationProvider fromValue(String value) {
        return Arrays.stream(values())
                .filter(provider -> provider.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported integration provider: " + value));
    }
}
