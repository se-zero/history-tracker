package com.history.pipeline_worker.source.jira;

import com.history.pipeline_worker.normalizer.RefsExtractor;
import com.history.pipeline_worker.dto.NormalizedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JiraNormalizer: Jira search API 결과 → Issue 이벤트 목록 변환.
 * extractPlainText() 재귀 파싱 로직이 핵심 테스트 대상.
 */
class JiraNormalizerTest {

    private JiraNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new JiraNormalizer(new RefsExtractor());
    }

    // ─── null / 빈 입력 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null 입력 → 빈 이벤트 목록")
    void normalizeIssues_nullInput_returnsEmpty() {
        assertThat(normalizer.normalizeIssues(null)).isEmpty();
    }

    @Test
    @DisplayName("issues 키 없는 Map → 빈 이벤트 목록")
    void normalizeIssues_noIssuesKey_returnsEmpty() {
        assertThat(normalizer.normalizeIssues(Map.of("total", 0))).isEmpty();
    }

    @Test
    @DisplayName("issues 빈 리스트 → 빈 이벤트 목록")
    void normalizeIssues_emptyIssuesList_returnsEmpty() {
        assertThat(normalizer.normalizeIssues(Map.of("issues", List.of()))).isEmpty();
    }

    // ─── 정상 변환 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 이슈 → Issue 이벤트 생성, 기본 필드 매핑")
    void normalizeIssues_normalIssue_createsIssueEvent() {
        Map<String, Object> result = buildSearchResult(List.of(
                buildIssue("PROJ-1", "Test Summary", null, "To Do", "Bug", "High",
                        "reporter-id", "Reporter Name", "reporter@test.com")
        ));

        List<NormalizedEvent> events = normalizer.normalizeIssues(result);

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.nodeType()).isEqualTo("Issue");
        assertThat(event.source()).isEqualTo("JIRA");
        assertThat(event.properties()).containsEntry("jira_key", "PROJ-1");
        assertThat(event.properties()).containsEntry("title", "Test Summary");
        assertThat(event.properties()).containsEntry("status", "To Do");
        assertThat(event.properties()).containsEntry("issue_type", "Bug");
        assertThat(event.properties()).containsEntry("priority", "High");
    }

    @Test
    @DisplayName("reporter 정보 → actor 필드 매핑")
    void normalizeIssues_reporterPresent_actorMapped() {
        Map<String, Object> result = buildSearchResult(List.of(
                buildIssue("PROJ-2", "Actor test", null, "Open", "Task", "Medium",
                        "account-xyz", "Jane Doe", "jane@example.com")
        ));

        NormalizedEvent event = normalizer.normalizeIssues(result).get(0);

        assertThat(event.actor().id()).isEqualTo("account-xyz");
        assertThat(event.actor().name()).isEqualTo("Jane Doe");
        assertThat(event.actor().email()).isEqualTo("jane@example.com");
    }

    @Test
    @DisplayName("Jira issue occurredAt은 created가 아닌 updated를 우선 사용")
    @SuppressWarnings("unchecked")
    void normalizeIssues_usesUpdatedForOccurredAt() {
        Map<String, Object> issue = buildIssue("PROJ-20", "Updated issue", null, "Open", "Task", "Medium",
                "account-xyz", "Jane Doe", "jane@example.com");
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        fields.put("created", "2024-03-15T10:30:00.000+0900");
        fields.put("updated", "2024-03-20T11:45:00.000+0900");

        NormalizedEvent event = normalizer.normalizeIssues(buildSearchResult(List.of(issue))).get(0);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2024-03-20T02:45:00Z"));
        assertThat(event.properties()).containsEntry("created_at", "2024-03-15T10:30:00.000+0900");
        assertThat(event.properties()).doesNotContainKey("updated_at");
    }

    @Test
    @DisplayName("reporter null → actor 필드 모두 null")
    void normalizeIssues_nullReporter_actorFieldsNull() {
        Map<String, Object> issue = buildIssue("PROJ-3", "No reporter", null, "Open", "Bug", "Low",
                null, null, null);

        // reporter 필드 자체를 null로 세팅
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        fields.put("reporter", null);

        NormalizedEvent event = normalizer.normalizeIssues(buildSearchResult(List.of(issue))).get(0);

        assertThat(event.actor().id()).isNull();
        assertThat(event.actor().name()).isNull();
        assertThat(event.actor().email()).isNull();
    }

    // ─── extractPlainText (description 파싱) ────────────────────────────────────

    @Test
    @DisplayName("description이 null → properties['body']는 빈 문자열")
    void normalizeIssues_nullDescription_emptyBody() {
        Map<String, Object> result = buildSearchResult(List.of(
                buildIssue("PROJ-4", "Summary", null, "Open", "Bug", "Low",
                        "id1", "Name", "email@test.com")
        ));

        NormalizedEvent event = normalizer.normalizeIssues(result).get(0);

        assertThat(event.properties()).containsEntry("body", "");
    }

    @Test
    @DisplayName("description이 단순 문자열 → 그대로 body에 포함")
    void normalizeIssues_stringDescription_usedAsBody() {
        Map<String, Object> issue = buildIssue("PROJ-5", "Summary", null, "Open", "Bug", "Low",
                "id1", "Name", "email@test.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        fields.put("description", "plain text description");

        NormalizedEvent event = normalizer.normalizeIssues(buildSearchResult(List.of(issue))).get(0);

        assertThat(event.properties()).containsEntry("body", "plain text description");
    }

    @Test
    @DisplayName("Jira Document 형식(중첩 content 배열) → 평문 텍스트 추출")
    void normalizeIssues_jiraDocumentFormat_extractsPlainText() {
        // {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"Hello world"}]}]}
        Map<String, Object> textNode = Map.of("type", "text", "text", "Hello world");
        Map<String, Object> paragraph = Map.of("type", "paragraph", "content", List.of(textNode));
        Map<String, Object> doc = Map.of("type", "doc", "content", List.of(paragraph));

        Map<String, Object> issue = buildIssue("PROJ-6", "Summary", null, "Open", "Story", "Medium",
                "id1", "Name", "email@test.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        fields.put("description", doc);

        NormalizedEvent event = normalizer.normalizeIssues(buildSearchResult(List.of(issue))).get(0);

        assertThat(event.properties()).containsEntry("body", "Hello world");
    }

    @Test
    @DisplayName("Jira Document 여러 paragraph → 스페이스로 연결")
    void normalizeIssues_multiParagraph_joinedWithSpace() {
        Map<String, Object> text1 = Map.of("type", "text", "text", "First");
        Map<String, Object> text2 = Map.of("type", "text", "text", "Second");
        Map<String, Object> para1 = Map.of("type", "paragraph", "content", List.of(text1));
        Map<String, Object> para2 = Map.of("type", "paragraph", "content", List.of(text2));
        Map<String, Object> doc = Map.of("type", "doc", "content", List.of(para1, para2));

        Map<String, Object> issue = buildIssue("PROJ-7", "Summary", null, "Open", "Task", "Low",
                "id1", "Name", "email@test.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        fields.put("description", doc);

        NormalizedEvent event = normalizer.normalizeIssues(buildSearchResult(List.of(issue))).get(0);

        assertThat((String) event.properties().get("body"))
                .contains("First")
                .contains("Second");
    }

    @Test
    @DisplayName("summary에 Jira 키 포함 시 refs 추출")
    void normalizeIssues_summaryWithJiraKey_refsExtracted() {
        Map<String, Object> result = buildSearchResult(List.of(
                buildIssue("PROJ-8", "Depends on AUTH-99 login", null, "Open", "Bug", "High",
                        "id1", "Name", "email@test.com")
        ));

        NormalizedEvent event = normalizer.normalizeIssues(result).get(0);

        assertThat(event.refs()).containsEntry("jiraKey", "AUTH-99");
    }

    @Test
    @DisplayName("parent 필드 있는 이슈 → refs에 parentJiraKey 포함")
    void normalizeIssues_withParent_parentKeyInRefs() {
        Map<String, Object> issue = buildIssue("PROJ-9", "Child story", null, "Open", "Story", "Medium",
                "id1", "Name", "email@test.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        fields.put("parent", Map.of("key", "EPIC-1"));

        NormalizedEvent event = normalizer.normalizeIssues(buildSearchResult(List.of(issue))).get(0);

        assertThat(event.refs()).containsEntry("parentJiraKey", "EPIC-1");
    }

    @Test
    @DisplayName("assignee 있는 이슈 → refs에 assigneeAccountId 포함")
    void normalizeIssues_withAssignee_assigneeAccountIdInRefs() {
        Map<String, Object> issue = buildIssue("PROJ-11", "Assigned issue", null, "Open", "Task", "Medium",
                "reporter-id", "Reporter", "reporter@test.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        Map<String, Object> assignee = new HashMap<>();
        assignee.put("accountId", "assignee-account-id");
        assignee.put("displayName", "Assignee Name");
        fields.put("assignee", assignee);

        NormalizedEvent event = normalizer.normalizeIssues(buildSearchResult(List.of(issue))).get(0);

        assertThat(event.refs()).containsEntry("assigneeId", "assignee-account-id");
    }

    @Test
    @DisplayName("parent 필드 없는 이슈 → refs에 parentJiraKey 없음")
    void normalizeIssues_withoutParent_noParentKeyInRefs() {
        Map<String, Object> result = buildSearchResult(List.of(
                buildIssue("PROJ-10", "Root epic", null, "Open", "Epic", "High",
                        "id1", "Name", "email@test.com")
        ));

        NormalizedEvent event = normalizer.normalizeIssues(result).get(0);

        assertThat(event.refs()).doesNotContainKey("parentJiraKey");
    }

    // ─── 헬퍼 메서드 ───────────────────────────────────────────────────────────

    private Map<String, Object> buildSearchResult(List<Map<String, Object>> issues) {
        Map<String, Object> result = new HashMap<>();
        result.put("issues", issues);
        return result;
    }

    private Map<String, Object> buildIssue(String key, String summary, String description,
                                            String statusName, String typeName, String priorityName,
                                            String reporterAccountId, String reporterDisplayName,
                                            String reporterEmail) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("summary", summary);
        fields.put("description", description);
        fields.put("created", "2024-03-15T10:30:00.000+0900");
        fields.put("updated", "2024-03-15T10:30:00.000+0900");
        fields.put("status", Map.of("name", statusName));
        fields.put("issuetype", Map.of("name", typeName));
        fields.put("priority", Map.of("name", priorityName));
        fields.put("assignee", null);

        if (reporterAccountId != null) {
            Map<String, Object> reporter = new HashMap<>();
            reporter.put("accountId", reporterAccountId);
            reporter.put("displayName", reporterDisplayName);
            reporter.put("emailAddress", reporterEmail);
            fields.put("reporter", reporter);
        } else {
            fields.put("reporter", null);
        }

        Map<String, Object> issue = new HashMap<>();
        issue.put("key", key);
        issue.put("fields", fields);
        return issue;
    }
}
