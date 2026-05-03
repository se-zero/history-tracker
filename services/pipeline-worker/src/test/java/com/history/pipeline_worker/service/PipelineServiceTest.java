package com.history.pipeline_worker.service;

import com.history.pipeline_worker.checkpoint.CheckpointData;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

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
        GitHubRawService.GitHubFetchContext context = githubContext();
        when(gitHubRawService.prepareFetchContext(request)).thenReturn(context);
        when(gitHubRawService.fetchMergedPullRequestPage(context, 1))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(buildPullRequest(10, "2024-02-02T00:00:00Z")), true));
        when(gitHubRawService.fetchCommitPrNumbers(context, List.of(buildPullRequest(10, "2024-02-02T00:00:00Z"))))
                .thenReturn(Map.of());
        when(gitHubRawService.fetchCommitPage(context, 1, Map.of()))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(
                        buildCommit("sha-1", "first", "2024-01-01T00:00:00Z"),
                        buildCommit("sha-2", "second", "2024-01-03T00:00:00Z")
                ), true));
        when(gitHubRawService.fetchIssuePage(context, 1))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(buildIssue(1, "2024-03-03T00:00:00Z")), true));
        when(eventPublisher.publishAll(anyList())).thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = pipelineService.normalizeGitHub(request);

        assertThat(queued).isEqualTo(4);
        verify(checkpointManager).updateGitHubCommits(Instant.parse("2024-01-03T00:00:00Z"));
        verify(checkpointManager).updateGitHubPullRequests(Instant.parse("2024-02-02T00:00:00Z"));
        verify(checkpointManager).updateGitHubIssues(Instant.parse("2024-03-03T00:00:00Z"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NormalizedEvent>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher, times(3)).publishAll(eventsCaptor.capture());
        assertThat(eventsCaptor.getAllValues()).extracting(events -> events.get(0).nodeType())
                .containsExactly("PullRequest", "ChangeSet", "Communication");
    }

    @Test
    @DisplayName("GitHub 발행 실패 시 체크포인트 미갱신")
    void normalizeGitHub_publishFailure_doesNotUpdateCheckpoint() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "owner/repo", Map.of());
        GitHubRawService.GitHubFetchContext context = githubContext();
        when(gitHubRawService.prepareFetchContext(request)).thenReturn(context);
        when(gitHubRawService.fetchMergedPullRequestPage(context, 1))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(buildPullRequest(10, "2024-02-02T00:00:00Z")), true));
        when(eventPublisher.publishAll(anyList())).thenThrow(new IllegalStateException("publish failed"));

        assertThatThrownBy(() -> pipelineService.normalizeGitHub(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish failed");
        verify(checkpointManager, never()).updateGitHubCommits(any());
        verify(checkpointManager, never()).updateGitHubPullRequests(any());
        verify(checkpointManager, never()).updateGitHubIssues(any());
    }

    @Test
    @DisplayName("Slack 이벤트는 채널별로 발행하고 전체 최대 occurredAt으로 체크포인트 갱신")
    void normalizeSlack_publishesPerChannelAndUpdatesCheckpointWithMaxOccurredAt() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", null, Map.of());
        Map<String, Object> firstChannel = buildSlackChannel(
                "general",
                "C001",
                List.of(buildSlackMessage("U001", "first", "1714000000.000000"))
        );
        Map<String, Object> secondChannel = buildSlackChannel(
                "dev",
                "C002",
                List.of(buildSlackMessage("U002", "second", "1714000100.000000"))
        );
        SlackRawService.SlackFetchContext context = slackContext();
        when(slackRawService.prepareFetchContext(request)).thenReturn(context);
        when(slackRawService.fetchChannels(context)).thenReturn(List.of(
                Map.of("id", "C001", "name", "general"),
                Map.of("id", "C002", "name", "dev")
        ));
        when(slackRawService.fetchHistoryPage(context, Map.of("id", "C001", "name", "general"), null))
                .thenReturn(new SlackRawService.SlackHistoryPage(firstChannel, null, true));
        when(slackRawService.fetchHistoryPage(context, Map.of("id", "C002", "name", "dev"), null))
                .thenReturn(new SlackRawService.SlackHistoryPage(secondChannel, null, true));
        when(eventPublisher.publishAll(anyList())).thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = pipelineService.normalizeSlack(request);

        assertThat(queued).isEqualTo(2);
        verify(eventPublisher, times(2)).publishAll(anyList());
        verify(checkpointManager).updateSlack(Instant.ofEpochSecond(1714000100L));
    }

    private SlackRawService.SlackFetchContext slackContext() {
        return new SlackRawService.SlackFetchContext(
                "Bearer token",
                null,
                Map.of()
        );
    }

    private GitHubRawService.GitHubFetchContext githubContext() {
        return new GitHubRawService.GitHubFetchContext(
                "Bearer token",
                "owner",
                "repo",
                new CheckpointData.GitHubCheckpoint()
        );
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

    private Map<String, Object> buildSlackChannel(String name, String id, List<Map<String, Object>> messages) {
        Map<String, Object> channel = new HashMap<>();
        channel.put("channelName", name);
        channel.put("channelId", id);
        channel.put("messages", messages);
        channel.put("threads", List.of());
        return channel;
    }

    private Map<String, Object> buildSlackMessage(String userId, String text, String ts) {
        Map<String, Object> message = new HashMap<>();
        message.put("user", userId);
        message.put("text", text);
        message.put("ts", ts);
        return message;
    }
}
