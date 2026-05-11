package com.history.pipeline_worker.service;

import java.util.Optional;

public record ProjectCollectionContext(
        String projectId,
        GitHubIntegration github,
        Optional<JiraIntegration> jira,
        Optional<SlackIntegration> slack
) {
    public ProjectCollectionContext {
        jira = jira != null ? jira : Optional.empty();
        slack = slack != null ? slack : Optional.empty();
    }
}
