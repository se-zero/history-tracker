package com.history.pipeline_worker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.integration.resolver.type", havingValue = "noop", matchIfMissing = true)
public class NoopProjectIntegrationResolver implements ProjectIntegrationResolver {

    @Override
    public Optional<ProjectCollectionContext> resolveGitHubPullRequestWebhook(GitHubWebhookPayload payload) {
        log.warn("No project integration resolver is configured for repository={}, repositoryId={}, installationId={}",
                payload.repositoryFullName(), payload.repositoryId(), payload.installationId());
        return Optional.empty();
    }
}
