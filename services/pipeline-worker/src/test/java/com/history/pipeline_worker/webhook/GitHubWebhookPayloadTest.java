package com.history.pipeline_worker.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookPayloadTest {

    // closed + merged=true는 병합된 PR로 판정돼야 한다.
    @Test
    void isMergedPullRequest_closedAndMerged_true() {
        GitHubWebhookPayload payload = new GitHubWebhookPayload(
                "closed", true, "octocat/repo", 1L, 100L, "main");

        assertThat(payload.isMergedPullRequest()).isTrue();
    }

    // closed여도 merged=false(단순 close)면 병합된 PR이 아니다.
    @Test
    void isMergedPullRequest_closedButNotMerged_false() {
        GitHubWebhookPayload payload = new GitHubWebhookPayload(
                "closed", false, "octocat/repo", 1L, 100L, "main");

        assertThat(payload.isMergedPullRequest()).isFalse();
    }

    // action이 closed가 아니면 merged 값과 무관하게 false여야 한다 —
    // merged만 보고 판정하는 구현이면 이 케이스가 실패한다.
    @Test
    void isMergedPullRequest_notClosedAction_falseRegardlessOfMerged() {
        GitHubWebhookPayload payload = new GitHubWebhookPayload(
                "opened", true, "octocat/repo", 1L, 100L, "main");

        assertThat(payload.isMergedPullRequest()).isFalse();
    }

    // baseRef는 생성자에 넘긴 값 그대로 보존돼야 한다.
    @Test
    void baseRef_preservesGivenValue() {
        GitHubWebhookPayload payload = new GitHubWebhookPayload(
                "closed", true, "octocat/repo", 1L, 100L, "develop");

        assertThat(payload.baseRef()).isEqualTo("develop");
    }

    // baseRef가 null로 전달돼도 그대로 null로 보존돼야 한다(임의로 기본값을 채우지 않음).
    @Test
    void baseRef_preservesNull() {
        GitHubWebhookPayload payload = new GitHubWebhookPayload(
                "closed", true, "octocat/repo", 1L, 100L, null);

        assertThat(payload.baseRef()).isNull();
    }
}
