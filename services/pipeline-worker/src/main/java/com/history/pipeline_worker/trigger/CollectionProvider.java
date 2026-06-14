package com.history.pipeline_worker.trigger;

public enum CollectionProvider {
    GITHUB,
    JIRA,
    SLACK;

    public static CollectionProvider fromPath(String value) {
        return switch (value) {
            case "github" -> GITHUB;
            case "jira" -> JIRA;
            case "slack" -> SLACK;
            default -> throw new IllegalArgumentException("Unsupported collection provider: " + value);
        };
    }
}
