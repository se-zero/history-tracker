package com.history.pipeline_worker.webhook;

public record GitHubWebhookPayload(
        String action,
        boolean merged,
        String repositoryFullName,
        Long repositoryId,
        Long installationId,
        String baseRef
) {
    public boolean isMergedPullRequest() {
        return "closed".equals(action) && merged;
    }
}
