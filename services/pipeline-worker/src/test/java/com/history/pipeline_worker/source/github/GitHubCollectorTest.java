package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.checkpoint.CheckpointService;
import com.history.pipeline_worker.collection.CollectionProvider;
import com.history.pipeline_worker.collection.ProjectIntegrationRepository;
import com.history.pipeline_worker.common.crypto.CredentialCryptoService;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.dto.RawFetchRequest;
import com.history.pipeline_worker.messaging.EventPublisher;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubCollectorTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final UUID PROJECT_UUID = UUID.fromString(PROJECT_ID);
    private static final byte[] INSTALLATION_TOKEN = new byte[] {1};
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private GitHubRawService rawService;
    @Mock
    private EventPublisher eventPublisher;
    @Mock
    private CheckpointService checkpointService;
    @Mock
    private CredentialCryptoService credentialCryptoService;

    private GitHubCollector collector;

    @BeforeEach
    void setUp() {
        collector = new GitHubCollector(
                rawService,
                new GitHubNormalizer(new RefsExtractor()),
                eventPublisher,
                checkpointService,
                credentialCryptoService,
                CLOCK
        );
    }

    @Test
    @DisplayName("체크포인트는 nodeType별 최대 occurredAt으로 갱신한다")
    void collect_updatesCursorsByNodeTypeMaxOccurredAt() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "owner/repo", Map.of());
        GitHubRawService.GitHubFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.GITHUB)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, GitHubCheckpoint.empty())).thenReturn(context);
        when(rawService.fetchMergedPullRequestPage(context, 1))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(buildPullRequest(10, "2024-02-02T00:00:00Z")), true));
        when(rawService.fetchCommitPrNumbers(context, List.of(buildPullRequest(10, "2024-02-02T00:00:00Z"))))
                .thenReturn(Map.of());
        when(rawService.fetchCommitPage(context, 1, Map.of()))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(
                        buildCommit("sha-1", "first", "2024-01-01T00:00:00Z"),
                        buildCommit("sha-2", "second", "2024-01-03T00:00:00Z")
                ), true));
        when(rawService.fetchIssuePage(context, 1))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(buildIssue(1, "2024-03-03T00:00:00Z")), true));
        when(eventPublisher.publishAll(anyList()))
                .thenAnswer(invocation -> invocation.<List<NormalizedEvent>>getArgument(0).size());

        int queued = collector.collect(PROJECT_ID, request);

        assertThat(queued).isEqualTo(4);
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.GITHUB,
                GitHubCheckpoint.COMMITS_CURSOR, Instant.parse("2024-01-03T00:00:00Z"));
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.GITHUB,
                GitHubCheckpoint.PULL_REQUESTS_CURSOR, Instant.parse("2024-02-02T00:00:00Z"));
        verify(checkpointService).updateCursor(PROJECT_ID, CollectionProvider.GITHUB,
                GitHubCheckpoint.ISSUES_CURSOR, Instant.parse("2024-03-03T00:00:00Z"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NormalizedEvent>> eventsCaptor = ArgumentCaptor.forClass(List.class);
        verify(eventPublisher, times(3)).publishAll(eventsCaptor.capture());
        assertThat(eventsCaptor.getAllValues()).extracting(events -> events.get(0).nodeType())
                .containsExactly("PullRequest", "ChangeSet", "Communication");
    }

    @Test
    @DisplayName("발행 실패 시 체크포인트를 전진시키지 않는다")
    void collect_publishFailure_doesNotUpdateCursor() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "owner/repo", Map.of());
        GitHubRawService.GitHubFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.GITHUB)).thenReturn(Map.of());
        when(rawService.prepareFetchContext(request, GitHubCheckpoint.empty())).thenReturn(context);
        when(rawService.fetchMergedPullRequestPage(context, 1))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(buildPullRequest(10, "2024-02-02T00:00:00Z")), true));
        when(eventPublisher.publishAll(anyList())).thenThrow(new IllegalStateException("publish failed"));

        assertThatThrownBy(() -> collector.collect(PROJECT_ID, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("publish failed");
        verify(checkpointService, never()).updateCursor(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("저장된 커서를 fetch context에 전달한다")
    void collect_passesStoredCursorsToFetchContext() {
        RawFetchRequest request = new RawFetchRequest("Bearer token", "owner/repo", Map.of());
        GitHubCheckpoint expected = new GitHubCheckpoint(
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z"),
                Instant.parse("2024-01-03T00:00:00Z")
        );
        GitHubRawService.GitHubFetchContext context = fetchContext();
        when(checkpointService.loadCursors(PROJECT_ID, CollectionProvider.GITHUB)).thenReturn(Map.of(
                GitHubCheckpoint.COMMITS_CURSOR, Instant.parse("2024-01-01T00:00:00Z"),
                GitHubCheckpoint.PULL_REQUESTS_CURSOR, Instant.parse("2024-01-02T00:00:00Z"),
                GitHubCheckpoint.ISSUES_CURSOR, Instant.parse("2024-01-03T00:00:00Z")
        ));
        when(rawService.prepareFetchContext(request, expected)).thenReturn(context);
        when(rawService.fetchMergedPullRequestPage(context, 1))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(), true));
        when(rawService.fetchCommitPrNumbers(context, List.of())).thenReturn(Map.of());
        when(rawService.fetchCommitPage(context, 1, Map.of()))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(), true));
        when(rawService.fetchIssuePage(context, 1))
                .thenReturn(new GitHubRawService.GitHubPage(List.of(), true));

        collector.collect(PROJECT_ID, request);

        verify(rawService).prepareFetchContext(request, expected);
    }

    @Test
    void resolveFetchRequest_buildsBearerRequestWithBranchOption() {
        when(credentialCryptoService.decrypt(INSTALLATION_TOKEN)).thenReturn("gh-token");

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(githubRow(
                Map.of("repository_full_name", "owner/repo", "branch", "main"),
                Instant.parse("2026-01-01T01:00:00Z")
        ));

        assertThat(result).hasValueSatisfying(request -> {
            assertThat(request.credentials()).isEqualTo("Bearer gh-token");
            assertThat(request.projectKey()).isEqualTo("owner/repo");
            assertThat(request.options()).containsEntry("branch", "main");
        });
    }

    @Test
    void resolveFetchRequest_withoutBranch_omitsOption() {
        when(credentialCryptoService.decrypt(INSTALLATION_TOKEN)).thenReturn("gh-token");

        Optional<RawFetchRequest> result = collector.resolveFetchRequest(githubRow(
                Map.of("repository_full_name", "owner/repo"),
                Instant.parse("2026-01-01T01:00:00Z")
        ));

        assertThat(result).hasValueSatisfying(request -> assertThat(request.options()).isEmpty());
    }

    @Test
    void resolveFetchRequest_expiredInstallationToken_isEmpty() {
        // 만료는 설정 오류가 아니라 "지금은 수집 불가"다 — 예외가 아니라 empty로 신호한다.
        Optional<RawFetchRequest> result = collector.resolveFetchRequest(githubRow(
                Map.of("repository_full_name", "owner/repo"),
                Instant.parse("2025-12-31T23:59:59Z")
        ));

        assertThat(result).isEmpty();
    }

    @Test
    void resolveFetchRequest_missingInstallationToken_isEmpty() {
        ProjectIntegrationRepository.IntegrationRow row = new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "github", Map.of("repository_full_name", "owner/repo"), null, null, null);

        assertThat(collector.resolveFetchRequest(row)).isEmpty();
    }

    @Test
    void resolveFetchRequest_missingRepositoryFullName_throwsConfigurationError() {
        when(credentialCryptoService.decrypt(INSTALLATION_TOKEN)).thenReturn("gh-token");

        assertThatThrownBy(() -> collector.resolveFetchRequest(githubRow(
                Map.of("repository_id", 123L),
                Instant.parse("2026-01-01T01:00:00Z")
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing external_ref value: repository_full_name");
    }

    private ProjectIntegrationRepository.IntegrationRow githubRow(Map<String, Object> externalRef, Instant expiresAt) {
        return new ProjectIntegrationRepository.IntegrationRow(
                PROJECT_UUID, "github", externalRef, null, INSTALLATION_TOKEN, expiresAt);
    }

    private GitHubRawService.GitHubFetchContext fetchContext() {
        return new GitHubRawService.GitHubFetchContext(
                "Bearer token", "owner", "repo", null, GitHubCheckpoint.empty(), new HashMap<>());
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
