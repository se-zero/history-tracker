package com.history.pipeline_worker.collection;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookIntegrationResolutionTest {

    @Test
    void incrementalDisabled_returnsIncrementalDisabledStatusWithEmptyContexts() {
        GitHubWebhookIntegrationResolution result = GitHubWebhookIntegrationResolution.incrementalDisabled();

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.INCREMENTAL_DISABLED);
        assertThat(result.contexts()).isEmpty();
    }

    // 브랜치 불일치 팩토리도 다른 비-ready 팩토리와 동일하게 빈 contexts를 돌려준다는 계약을 고정한다.
    @Test
    void branchMismatch_returnsBranchMismatchStatusWithEmptyContexts() {
        GitHubWebhookIntegrationResolution result = GitHubWebhookIntegrationResolution.branchMismatch();

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.BRANCH_MISMATCH);
        assertThat(result.contexts()).isEmpty();
    }

    // ready(List)가 전달받은 리스트를 그대로 보존하는지 확인한다 — 팬아웃의 기반이 되는 계약이다.
    @Test
    void ready_preservesGivenContextsList() {
        ProjectCollectionContext contextA = new ProjectCollectionContext("11111111-1111-1111-1111-111111111111", Map.of());
        ProjectCollectionContext contextB = new ProjectCollectionContext("22222222-2222-2222-2222-222222222222", Map.of());

        GitHubWebhookIntegrationResolution result = GitHubWebhookIntegrationResolution.ready(List.of(contextA, contextB));

        assertThat(result.status()).isEqualTo(GitHubWebhookIntegrationResolution.Status.READY);
        assertThat(result.contexts()).containsExactly(contextA, contextB);
    }
}
