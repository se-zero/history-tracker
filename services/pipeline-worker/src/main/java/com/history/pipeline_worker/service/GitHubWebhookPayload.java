package com.history.pipeline_worker.service;

public record GitHubWebhookPayload(
        String action,
        boolean merged,
        String repositoryFullName,
        Long repositoryId,
        Long installationId
) {
    public boolean isMergedPullRequest() {
        return "closed".equals(action) && merged;
    }
}
