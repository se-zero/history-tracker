package com.history.pipeline_worker.source.github;

import java.time.Instant;
import java.util.Map;

/**
 * GitHub 수집 커서 snapshot. 타입별로 독립이라 재시작 시 완료된 타입은 건너뛴다.
 *
 * <p>cursor_key 문자열은 GitHub 수집이 소유한다 — checkpoint 저장소는 키를 해석하지 않는다.</p>
 */
public record GitHubCheckpoint(
        Instant commitsScannedAt,
        Instant pullRequestsScannedAt,
        Instant issuesScannedAt
) {

    public static final String COMMITS_CURSOR = "github_commits";
    public static final String PULL_REQUESTS_CURSOR = "github_pull_requests";
    public static final String ISSUES_CURSOR = "github_issues";

    public static GitHubCheckpoint empty() {
        return new GitHubCheckpoint(null, null, null);
    }

    public static GitHubCheckpoint from(Map<String, Instant> cursors) {
        return new GitHubCheckpoint(
                cursors.get(COMMITS_CURSOR),
                cursors.get(PULL_REQUESTS_CURSOR),
                cursors.get(ISSUES_CURSOR)
        );
    }
}
