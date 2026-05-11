package com.history.pipeline_worker.service;

import java.util.Optional;

public interface ProjectIntegrationResolver {

    Optional<ProjectCollectionContext> resolveGitHubPullRequestWebhook(GitHubWebhookPayload payload);
}
