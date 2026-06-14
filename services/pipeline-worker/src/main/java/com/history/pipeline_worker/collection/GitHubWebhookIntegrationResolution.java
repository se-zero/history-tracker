package com.history.pipeline_worker.collection;

public record GitHubWebhookIntegrationResolution(
        Status status,
        ProjectCollectionContext context
) {

    public static GitHubWebhookIntegrationResolution ready(ProjectCollectionContext context) {
        return new GitHubWebhookIntegrationResolution(Status.READY, context);
    }

    public static GitHubWebhookIntegrationResolution tokenRefreshRequired() {
        return new GitHubWebhookIntegrationResolution(Status.TOKEN_REFRESH_REQUIRED, null);
    }

    public static GitHubWebhookIntegrationResolution notFound() {
        return new GitHubWebhookIntegrationResolution(Status.NOT_FOUND, null);
    }

    public enum Status {
        READY,
        TOKEN_REFRESH_REQUIRED,
        NOT_FOUND
    }
}
