package com.history.pipeline_worker.source.github;

import com.history.pipeline_worker.normalizer.RefsExtractor;
import com.history.pipeline_worker.dto.NormalizedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GitHubNormalizer: raw GitHub API 데이터를 NormalizedEvent 목록으로 변환.
 * RefsExtractor는 실제 객체 사용 — 정규식 로직이 통합 동작에 영향을 주기 때문.
 */
class GitHubNormalizerTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";

    private GitHubNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new GitHubNormalizer(new RefsExtractor());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // normalizeCommits
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("빈 리스트 입력 → 빈 이벤트 목록")
    void normalizeCommits_emptyList_returnsEmpty() {
        assertThat(normalizer.normalizeCommits(PROJECT_ID, List.of())).isEmpty();
    }

    @Test
    @DisplayName("머지 커밋(parents 2개)은 결과에서 제외")
    void normalizeCommits_mergeCommit_filtered() {
        Map<String, Object> mergeCommit = buildCommit("sha-merge", "merge commit", "2024-01-01T00:00:00Z",
                List.of(Map.of("sha", "p1"), Map.of("sha", "p2")));  // parent 2개

        List<NormalizedEvent> events = normalizer.normalizeCommits(PROJECT_ID, List.of(mergeCommit));

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("일반 커밋(parent 1개) → ChangeSet 이벤트 생성")
    void normalizeCommits_normalCommit_createsChangSetEvent() {
        Map<String, Object> commit = buildCommit("abc123", "feat: add payment", "2024-03-15T10:00:00Z",
                List.of(Map.of("sha", "parent-1")));

        List<NormalizedEvent> events = normalizer.normalizeCommits(PROJECT_ID, List.of(commit));

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.projectId()).isEqualTo(PROJECT_ID);
        assertThat(event.nodeType()).isEqualTo("ChangeSet");
        assertThat(event.source()).isEqualTo("GITHUB");
        assertThat(event.properties()).containsEntry("hash", "abc123");
        assertThat(event.properties()).containsEntry("message", "feat: add payment");
    }

    @Test
    @DisplayName("커밋 메시지에 Jira 키 포함 시 refs 자동 추출")
    void normalizeCommits_messageWithJiraKey_refsPopulated() {
        Map<String, Object> commit = buildCommit("sha1", "fix: PAYMENT-301 null pointer", "2024-03-15T10:00:00Z",
                List.of(Map.of("sha", "p1")));

        NormalizedEvent event = normalizer.normalizeCommits(PROJECT_ID, List.of(commit)).get(0);

        assertThat(event.refs()).containsEntry("issueKey", "PAYMENT-301");
    }

    @Test
    @DisplayName("raw commit prNumber가 있으면 refs.prNumber에 구조적으로 반영")
    void normalizeCommits_rawPrNumber_refsPopulated() {
        Map<String, Object> commit = buildCommit("sha1", "fix: no PR number in message", "2024-03-15T10:00:00Z",
                List.of(Map.of("sha", "p1")));
        commit.put("prNumber", "42");

        NormalizedEvent event = normalizer.normalizeCommits(PROJECT_ID, List.of(commit)).get(0);

        assertThat(event.refs()).containsEntry("prNumber", "42");
    }

    @Test
    @DisplayName("commit occurredAt은 author date가 아닌 committer date를 우선 사용")
    @SuppressWarnings("unchecked")
    void normalizeCommits_usesCommitterDateForOccurredAt() {
        Map<String, Object> commit = buildCommit("sha1", "feat: commit date", "2024-03-15T10:00:00Z",
                List.of(Map.of("sha", "p1")));
        Map<String, Object> commitDetail = (Map<String, Object>) commit.get("commit");
        commitDetail.put("committer", Map.of("date", "2024-03-16T12:30:00Z"));

        NormalizedEvent event = normalizer.normalizeCommits(PROJECT_ID, List.of(commit)).get(0);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2024-03-16T12:30:00Z"));
        assertThat(event.properties()).doesNotContainKeys("authored_at", "committed_at");
    }

    @Test
    @DisplayName("GitHub 계정(author)이 null이면 authorLogin은 commit.author.name으로 대체하고 git email은 넣지 않는다")
    void normalizeCommits_nullGitHubAuthor_fallbackToNameWithoutEmail() {
        Map<String, Object> commitDetail = new HashMap<>();
        commitDetail.put("message", "init");
        commitDetail.put("author", Map.of("name", "John Doe", "email", "john@example.com", "date", "2024-01-01T00:00:00Z"));

        Map<String, Object> commit = new HashMap<>();
        commit.put("sha", "sha1");
        commit.put("commit", commitDetail);
        commit.put("author", null);  // GitHub 계정 null
        commit.put("parents", List.of(Map.of("sha", "p1")));

        NormalizedEvent event = normalizer.normalizeCommits(PROJECT_ID, List.of(commit)).get(0);

        // login이 없으므로 name이 id 필드에 사용되어야 함
        assertThat(event.actor().id()).isEqualTo("John Doe");
        assertThat(event.actor().name()).isEqualTo("John Doe");
        // git config 이메일은 개인정보라 ActorDto에 넣지 않는다
        assertThat(event.actor().email()).isNull();
    }

    @Test
    @DisplayName("GitHub 계정(author) 있음 + 보강된 프로필 name/email 있음 → ActorDto는 프로필 정보 사용")
    void normalizeCommits_authorWithEnrichedProfile_usesProfileNameAndEmail() {
        Map<String, Object> commit = buildCommit("sha1", "feat: x", "2024-03-15T10:00:00Z",
                List.of(Map.of("sha", "p1")));
        @SuppressWarnings("unchecked")
        Map<String, Object> ghAuthor = (Map<String, Object>) commit.get("author");
        ghAuthor.put("name", "Octocat Profile");   // GitHubRawService.enrichCommits가 보강한 프로필 name
        ghAuthor.put("email", "octocat@github.com"); // 보강된 프로필 email

        NormalizedEvent event = normalizer.normalizeCommits(PROJECT_ID, List.of(commit)).get(0);

        assertThat(event.actor().id()).isEqualTo("test-user");
        assertThat(event.actor().name()).isEqualTo("Octocat Profile");
        assertThat(event.actor().email()).isEqualTo("octocat@github.com");
    }

    @Test
    @DisplayName("GitHub 계정(author) 있음 + 보강된 프로필 name 없음 → name은 login으로 대체")
    void normalizeCommits_authorWithoutEnrichedProfileName_fallsBackToLogin() {
        Map<String, Object> commit = buildCommit("sha1", "feat: y", "2024-03-15T10:00:00Z",
                List.of(Map.of("sha", "p1")));
        // ghAuthor에는 login만 있고 보강된 name/email이 없는 상태 (buildCommit 기본값)

        NormalizedEvent event = normalizer.normalizeCommits(PROJECT_ID, List.of(commit)).get(0);

        assertThat(event.actor().id()).isEqualTo("test-user");
        assertThat(event.actor().name()).isEqualTo("test-user");
        assertThat(event.actor().email()).isNull();
    }

    @Test
    @DisplayName("files 데이터 있으면 properties['files']에 path·diff·additions·deletions 포함")
    void normalizeCommits_withFiles_filesMappedCorrectly() {
        Map<String, Object> file = new HashMap<>();
        file.put("filename", "src/Foo.java");
        file.put("patch", "@@ -1 +1 @@");
        file.put("additions", 5);
        file.put("deletions", 2);

        Map<String, Object> commit = buildCommit("sha1", "refactor", "2024-01-01T00:00:00Z",
                List.of(Map.of("sha", "p1")));
        commit.put("files", List.of(file));

        NormalizedEvent event = normalizer.normalizeCommits(PROJECT_ID, List.of(commit)).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) event.properties().get("files");
        assertThat(files).hasSize(1);
        assertThat(files.get(0)).containsEntry("path", "src/Foo.java")
                .containsEntry("diff", "@@ -1 +1 @@");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // normalizePullRequests
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PR → PullRequest 이벤트 생성, 기본 필드 매핑")
    void normalizePullRequests_normalPR_createsPullRequestEvent() {
        Map<String, Object> pr = buildPullRequest(42, "feat: new feature", "open", "main", "2024-04-01T00:00:00Z");

        List<NormalizedEvent> events = normalizer.normalizePullRequests(PROJECT_ID, List.of(pr));

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.nodeType()).isEqualTo("PullRequest");
        assertThat(event.properties()).containsEntry("pr_number", 42);
        assertThat(event.properties()).containsEntry("state", "open");
        assertThat(event.properties()).containsEntry("base_branch", "main");
    }

    @Test
    @DisplayName("PR body에 Jira 키 포함 시 refs 추출")
    void normalizePullRequests_bodyWithJiraKey_refsExtracted() {
        Map<String, Object> pr = buildPullRequest(10, "fix login", "closed", "develop", "2024-04-01T00:00:00Z");
        pr.put("body", "Implements AUTH-55 login flow");

        NormalizedEvent event = normalizer.normalizePullRequests(PROJECT_ID, List.of(pr)).get(0);

        assertThat(event.refs()).containsEntry("issueKey", "AUTH-55");
    }

    @Test
    @DisplayName("PR occurredAt은 merged_at을 우선 사용")
    void normalizePullRequests_usesMergedAtForOccurredAt() {
        Map<String, Object> pr = buildPullRequest(10, "merged PR", "closed", "main", "2024-04-01T00:00:00Z");
        pr.put("merged_at", "2024-04-05T09:00:00Z");

        NormalizedEvent event = normalizer.normalizePullRequests(PROJECT_ID, List.of(pr)).get(0);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2024-04-05T09:00:00Z"));
        assertThat(event.properties()).containsEntry("created_at", "2024-04-01T00:00:00Z");
        assertThat(event.properties()).doesNotContainKey("merged_at");
    }

    @Test
    @DisplayName("PR user가 null이면 actor 필드 모두 null")
    void normalizePullRequests_nullUser_actorFieldsNull() {
        Map<String, Object> pr = buildPullRequest(1, "title", "open", "main", "2024-01-01T00:00:00Z");
        pr.put("user", null);

        NormalizedEvent event = normalizer.normalizePullRequests(PROJECT_ID, List.of(pr)).get(0);

        assertThat(event.actor().id()).isNull();
        assertThat(event.actor().name()).isNull();
    }

    @Test
    @DisplayName("user.name이 있으면 displayName으로 사용, 없으면 login 사용")
    @SuppressWarnings("unchecked")
    void normalizePullRequests_resolveDisplayName_nameOverLogin() {
        Map<String, Object> pr = buildPullRequest(1, "title", "open", "main", "2024-01-01T00:00:00Z");
        // user에 name 필드 추가
        Map<String, Object> user = (Map<String, Object>) pr.get("user");
        user.put("name", "Alice Kim");

        NormalizedEvent eventWithName = normalizer.normalizePullRequests(PROJECT_ID, List.of(pr)).get(0);
        assertThat(eventWithName.actor().name()).isEqualTo("Alice Kim");

        // name 없는 user
        Map<String, Object> pr2 = buildPullRequest(2, "title2", "open", "main", "2024-01-01T00:00:00Z");
        NormalizedEvent eventWithoutName = normalizer.normalizePullRequests(PROJECT_ID, List.of(pr2)).get(0);
        assertThat(eventWithoutName.actor().name()).isEqualTo("test-user");  // login 값
    }

    // ─────────────────────────────────────────────────────────────────────────
    // normalizeIssues
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pull_request 키가 있는 항목은 필터링")
    void normalizeIssues_itemWithPullRequestKey_filtered() {
        Map<String, Object> item = buildIssue(101L, 1, "PR disguised as issue", null);
        item.put("pull_request", Map.of("url", "https://api.github.com/repos/..."));

        List<NormalizedEvent> events = normalizer.normalizeIssues(PROJECT_ID, List.of(item));

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("id가 없으면 skip")
    void normalizeIssues_missingId_skipped() {
        Map<String, Object> issue = buildIssue(101L, 7, "Bug report", "Some body");
        issue.remove("id");

        List<NormalizedEvent> events = normalizer.normalizeIssues(PROJECT_ID, List.of(issue));

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("정상 open 이슈 → Issue 이벤트, title/body 분리, status_category=open, closed_at 키 없음")
    void normalizeIssues_normalOpenIssue_createsIssueEvent() {
        Map<String, Object> issue = buildIssue(101L, 7, "Bug report", "Some body");

        List<NormalizedEvent> events = normalizer.normalizeIssues(PROJECT_ID, List.of(issue));

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.nodeType()).isEqualTo("Issue");
        assertThat(event.source()).isEqualTo("GITHUB");
        Map<String, Object> properties = event.properties();
        assertThat(properties).containsEntry("external_id", "101")
                .containsEntry("issue_key", "#7")
                .containsEntry("title", "Bug report")
                .containsEntry("body", "Some body")
                .containsEntry("status_category", "open")
                .containsEntry("status", "open");
        assertThat(properties).doesNotContainKey("closed_at");
        assertThat(properties).doesNotContainKeys("channel", "conversation_id", "url");
    }

    @Test
    @DisplayName("closed + state_reason=not_planned + closed_at → status_category=closed, status=not_planned, closed_at 존재")
    void normalizeIssues_closedWithStateReason_createsIssueEvent() {
        Map<String, Object> issue = buildIssue(102L, 8, "Won't fix", "detail");
        issue.put("state", "closed");
        issue.put("state_reason", "not_planned");
        issue.put("closed_at", "2024-03-05T00:00:00Z");

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, List.of(issue)).get(0);

        assertThat(event.properties()).containsEntry("status_category", "closed")
                .containsEntry("status", "not_planned")
                .containsEntry("closed_at", "2024-03-05T00:00:00Z");
    }

    @Test
    @DisplayName("closed + state_reason null → status=closed")
    void normalizeIssues_closedWithNullStateReason_statusClosed() {
        Map<String, Object> issue = buildIssue(103L, 9, "Fixed", "detail");
        issue.put("state", "closed");
        issue.put("closed_at", "2024-03-06T00:00:00Z");

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, List.of(issue)).get(0);

        assertThat(event.properties()).containsEntry("status_category", "closed")
                .containsEntry("status", "closed");
    }

    @Test
    @DisplayName("open인데 응답에 closed_at 값이 있어도(재오픈) closed_at 키를 넣지 않는다")
    void normalizeIssues_reopenedWithStaleClosedAt_noClosedAtKey() {
        Map<String, Object> issue = buildIssue(104L, 10, "Reopened", "detail");
        issue.put("state", "open");
        issue.put("closed_at", "2024-02-01T00:00:00Z");  // 과거 종료 이력이 응답에 남아있는 케이스

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, List.of(issue)).get(0);

        assertThat(event.properties()).containsEntry("status_category", "open")
                .containsEntry("status", "open");
        assertThat(event.properties()).doesNotContainKey("closed_at");
    }

    @Test
    @DisplayName("GitHub issue occurredAt은 updated_at을 우선 사용, 없으면 created_at")
    void normalizeIssues_usesUpdatedAtForOccurredAt() {
        Map<String, Object> issue = buildIssue(105L, 7, "Bug report", "Some body");
        issue.put("updated_at", "2024-03-03T12:00:00Z");

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, List.of(issue)).get(0);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2024-03-03T12:00:00Z"));
        assertThat(event.properties()).containsEntry("created_at", "2024-03-01T00:00:00Z");

        Map<String, Object> issueNoUpdatedAt = buildIssue(106L, 8, "No updated_at", "body");
        issueNoUpdatedAt.remove("updated_at");
        NormalizedEvent eventFallback = normalizer.normalizeIssues(PROJECT_ID, List.of(issueNoUpdatedAt)).get(0);
        assertThat(eventFallback.occurredAt()).isEqualTo(Instant.parse("2024-03-01T00:00:00Z"));
    }

    @Test
    @DisplayName("title/body는 합산하지 않고 별도 필드로 유지, body null이면 빈 문자열")
    void normalizeIssues_titleAndBody_notCombined() {
        Map<String, Object> issue = buildIssue(107L, 3, "My Title", "Detailed body");

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, List.of(issue)).get(0);

        assertThat(event.properties()).containsEntry("title", "My Title")
                .containsEntry("body", "Detailed body");

        Map<String, Object> issueNullBody = buildIssue(108L, 4, "Only Title", null);
        NormalizedEvent eventNullBody = normalizer.normalizeIssues(PROJECT_ID, List.of(issueNullBody)).get(0);
        assertThat(eventNullBody.properties()).containsEntry("title", "Only Title")
                .containsEntry("body", "");
    }

    @Test
    @DisplayName("assignees 2명(한 명은 name 보강, 한 명은 login만) → refs.assignees 매핑, login null 항목 제외")
    void normalizeIssues_assignees_mappedToRefs() {
        Map<String, Object> issue = buildIssue(109L, 11, "Needs help", "detail");
        Map<String, Object> withName = new HashMap<>();
        withName.put("login", "alice");
        withName.put("name", "Alice Kim");
        withName.put("email", "alice@example.com");
        Map<String, Object> withoutName = new HashMap<>();
        withoutName.put("login", "bob");
        Map<String, Object> nullLogin = new HashMap<>();
        nullLogin.put("login", null);
        issue.put("assignees", List.of(withName, withoutName, nullLogin));

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, List.of(issue)).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) event.refs().get("assignees");
        assertThat(assignees).hasSize(2);
        assertThat(assignees).anySatisfy(a -> {
            assertThat(a).containsEntry("id", "alice").containsEntry("name", "Alice Kim").containsEntry("email", "alice@example.com");
        });
        assertThat(assignees).anySatisfy(a -> {
            assertThat(a).containsEntry("id", "bob").containsEntry("name", "bob");
        });
    }

    @Test
    @DisplayName("assignees가 없거나 빈 배열이어도 refs.assignees는 빈 리스트로 키가 존재한다")
    void normalizeIssues_emptyAssignees_refsAssigneesKeyPresentAsEmptyList() {
        Map<String, Object> issueNoAssignees = buildIssue(110L, 12, "No assignees key", "detail");

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, List.of(issueNoAssignees)).get(0);

        assertThat(event.refs()).containsKey("assignees");
        assertThat((List<?>) event.refs().get("assignees")).isEmpty();

        Map<String, Object> issueEmptyAssignees = buildIssue(111L, 13, "Empty assignees", "detail");
        issueEmptyAssignees.put("assignees", List.of());
        NormalizedEvent eventEmpty = normalizer.normalizeIssues(PROJECT_ID, List.of(issueEmptyAssignees)).get(0);
        assertThat(eventEmpty.refs()).containsKey("assignees");
        assertThat((List<?>) eventEmpty.refs().get("assignees")).isEmpty();
    }

    @Test
    @DisplayName("body에 이슈 키가 있으면 refsExtractor를 거쳐 refs.issueKey로 추출")
    void normalizeIssues_bodyWithIssueKey_refsExtracted() {
        Map<String, Object> issue = buildIssue(112L, 14, "Fix bug", "Relates to HT-7");

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, List.of(issue)).get(0);

        assertThat(event.refs()).containsEntry("issueKey", "HT-7");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────────────────────────────────

    /** 커밋 raw 데이터 빌더 */
    private Map<String, Object> buildCommit(String sha, String message, String date, List<Object> parents) {
        Map<String, Object> authorDetail = new HashMap<>();
        authorDetail.put("name", "Test User");
        authorDetail.put("email", "test@example.com");
        authorDetail.put("date", date);

        Map<String, Object> commitDetail = new HashMap<>();
        commitDetail.put("message", message);
        commitDetail.put("author", authorDetail);
        commitDetail.put("committer", Map.of("date", date));

        Map<String, Object> ghAuthor = new HashMap<>();
        ghAuthor.put("login", "test-user");

        Map<String, Object> commit = new HashMap<>();
        commit.put("sha", sha);
        commit.put("commit", commitDetail);
        commit.put("author", ghAuthor);
        commit.put("parents", parents);
        return commit;
    }

    /** PR raw 데이터 빌더 */
    private Map<String, Object> buildPullRequest(int number, String title, String state,
                                                   String baseRef, String createdAt) {
        Map<String, Object> user = new HashMap<>();
        user.put("login", "test-user");

        Map<String, Object> base = new HashMap<>();
        base.put("ref", baseRef);

        Map<String, Object> pr = new HashMap<>();
        pr.put("number", number);
        pr.put("title", title);
        pr.put("state", state);
        pr.put("body", null);
        pr.put("created_at", createdAt);
        pr.put("user", user);
        pr.put("base", base);
        pr.put("merged_at", null);
        pr.put("html_url", "https://github.com/test/repo/pull/" + number);
        return pr;
    }

    /** 이슈 raw 데이터 빌더 (open 기본값) */
    private Map<String, Object> buildIssue(long id, int number, String title, String body) {
        Map<String, Object> user = new HashMap<>();
        user.put("login", "test-user");

        Map<String, Object> issue = new HashMap<>();
        issue.put("id", id);
        issue.put("number", number);
        issue.put("title", title);
        issue.put("body", body);
        issue.put("state", "open");
        issue.put("created_at", "2024-03-01T00:00:00Z");
        issue.put("updated_at", "2024-03-01T00:00:00Z");
        issue.put("user", user);
        issue.put("html_url", "https://github.com/test/repo/issues/" + number);
        return issue;
    }
}
