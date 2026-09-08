package com.history.pipeline_worker.collection;

import java.util.List;

public record GitHubWebhookIntegrationResolution(
        Status status,
        List<ProjectCollectionContext> contexts
) {

    // 팬아웃 대상 리스트를 외부에서 변경할 수 없게 방어적으로 복사한다 — ProjectCollectionContext의
    // compact 생성자와 같은 관례.
    public GitHubWebhookIntegrationResolution {
        contexts = List.copyOf(contexts);
    }

    public static GitHubWebhookIntegrationResolution ready(List<ProjectCollectionContext> contexts) {
        return new GitHubWebhookIntegrationResolution(Status.READY, contexts);
    }

    public static GitHubWebhookIntegrationResolution tokenRefreshRequired() {
        return new GitHubWebhookIntegrationResolution(Status.TOKEN_REFRESH_REQUIRED, List.of());
    }

    public static GitHubWebhookIntegrationResolution notFound() {
        return new GitHubWebhookIntegrationResolution(Status.NOT_FOUND, List.of());
    }

    public static GitHubWebhookIntegrationResolution incrementalDisabled() {
        return new GitHubWebhookIntegrationResolution(Status.INCREMENTAL_DISABLED, List.of());
    }

    public static GitHubWebhookIntegrationResolution branchMismatch() {
        return new GitHubWebhookIntegrationResolution(Status.BRANCH_MISMATCH, List.of());
    }

    public enum Status {
        READY,
        TOKEN_REFRESH_REQUIRED,
        NOT_FOUND,
        INCREMENTAL_DISABLED,
        BRANCH_MISMATCH
    }
}
