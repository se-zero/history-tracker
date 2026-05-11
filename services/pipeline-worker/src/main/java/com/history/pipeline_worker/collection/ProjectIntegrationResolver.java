package com.history.pipeline_worker.collection;

import com.history.pipeline_worker.webhook.GitHubWebhookPayload;

import java.util.Optional;

public interface ProjectIntegrationResolver {

    Optional<ProjectCollectionContext> resolveGitHubPullRequestWebhook(GitHubWebhookPayload payload);
}
