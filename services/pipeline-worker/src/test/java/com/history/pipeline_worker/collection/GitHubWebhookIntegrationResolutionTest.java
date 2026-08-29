package com.history.pipeline_worker.collection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookIntegrationResolutionTest {

    @Test
    void incrementalDisabled_returnsIncrementalDisabledStatusWithNullContext() {
        GitHubWebhookIntegrationResolution result = GitHubWebhookIntegrationResolution.incrementalDisabled();

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.INCREMENTAL_DISABLED);
        assertThat(result.context()).isNull();
    }
}
