package com.history.pipeline_worker.service;

import com.history.pipeline_worker.checkpoint.FileCheckpointManager;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import com.history.pipeline_worker.normalizer.GitHubNormalizer;
import com.history.pipeline_worker.normalizer.JiraNormalizer;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import com.history.pipeline_worker.normalizer.SlackNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PipelineServiceTest {

    @Mock
    private GitHubRawService gitHubRawService;
    @Mock
    private JiraRawService jiraRawService;
    @Mock
    private SlackRawService slackRawService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private FileCheckpointManager checkpointManager;

    private PipelineService pipelineService;

    @BeforeEach
    void setUp() {
        RefsExtractor refsExtractor = new RefsExtractor();
        pipelineService = new PipelineService(
                gitHubRawService,
                jiraRawService,
                slackRawService,
                new GitHubNormalizer(refsExtractor),
                new JiraNormalizer(refsExtractor),
                new SlackNormalizer(refsExtractor),
                eventPublisher,
                checkpointManager
        );
    }

    @Test
    @DisplayName("GitHub 체크포인트는 source+nodeType별 최대 occurredAt으로 갱신")
    void normalizeGitHub_updatesCheckpointsByGithubNodeTypeMaxOccurredAt() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "owner/repo", Map.of());
        Map<String, Object> raw = Map.of(
                "commits", List.of(
                        buildCommit("sha-1", "first", "2024-01-01T00:00:00Z"),
                        buildCommit("sha-2", "second", "2024-01-03T00:00:00Z")
                ),
                "pullRequests", List.of(buildPullRequest(10, "2024-02-02T00:00:00Z")),
                "issues", List.of(buildIssue(1, "2024-03-03T00:00:00Z"))
        );
        when(gitHubRawService.fetch(request)).thenReturn(raw);
        when(eventPublisher.publishAll(anyList())).thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        List<NormalizedEvent> events = pipelineService.normalizeGitHub(request);

        assertThat(events).hasSize(4);
        verify(checkpointManager).updateGitHubCommits(Instant.parse("2024-01-03T00:00:00Z"));
        verify(checkpointManager).updateGitHubPullRequests(Instant.parse("2024-02-02T00:00:00Z"));
        verify(checkpointManager).updateGitHubIssues(Instant.parse("2024-03-03T00:00:00Z"));
    }

    private Map<String, Object> buildCommit(String sha, String message, String committedAt) {
        Map<String, Object> commitDetail = new HashMap<>();
        commitDetail.put("message", message);
        commitDetail.put("author", Map.of("name", "Dev", "email", "dev@example.com", "date", "2024-01-01T00:00:00Z"));
        commitDetail.put("committer", Map.of("date", committedAt));

        Map<String, Object> commit = new HashMap<>();
        commit.put("sha", sha);
        commit.put("commit", commitDetail);
        commit.put("author", Map.of("login", "dev"));
        commit.put("parents", List.of(Map.of("sha", "parent")));
        return commit;
    }

    private Map<String, Object> buildPullRequest(int number, String mergedAt) {
        Map<String, Object> pr = new HashMap<>();
        pr.put("number", number);
        pr.put("title", "merged PR");
        pr.put("state", "closed");
        pr.put("body", null);
        pr.put("created_at", "2024-02-01T00:00:00Z");
        pr.put("merged_at", mergedAt);
        pr.put("user", Map.of("login", "dev"));
        pr.put("base", Map.of("ref", "main"));
        pr.put("html_url", "https://github.com/owner/repo/pull/" + number);
        return pr;
    }

    private Map<String, Object> buildIssue(int number, String updatedAt) {
        Map<String, Object> issue = new HashMap<>();
        issue.put("number", number);
        issue.put("title", "issue");
        issue.put("body", "body");
        issue.put("created_at", "2024-03-01T00:00:00Z");
        issue.put("updated_at", updatedAt);
        issue.put("user", Map.of("login", "dev"));
        issue.put("html_url", "https://github.com/owner/repo/issues/" + number);
        return issue;
    }
}
