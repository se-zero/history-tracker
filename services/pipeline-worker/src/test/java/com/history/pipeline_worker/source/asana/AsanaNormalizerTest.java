package com.history.pipeline_worker.source.asana;

import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AsanaNormalizer: AsanaRawService.AsanaTaskPage.body()({"data": [태스크...], "next_page": ...}) →
 * Issue 이벤트 목록 변환. LinearNormalizerTest 컨벤션(실객체 RefsExtractor 조합)을 미러하되,
 * Asana에는 이슈 키 체계·상태 필드·우선순위 필드가 없어 해당 키들은 아예 발행되지 않는다는 점이
 * Linear/Jira와 다르다. 봇 판정 신호도 없어 actor.bot은 항상 null이다.
 */
class AsanaNormalizerTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CREATED_AT = "2024-03-01T00:00:00.000Z";
    private static final String MODIFIED_AT = "2024-03-15T10:30:00.000Z";

    private AsanaNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new AsanaNormalizer(new RefsExtractor());
    }

    // ─── null / 빈 입력 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null 응답 → 빈 이벤트 목록")
    void normalizeTasks_nullResponseBody_returnsEmpty() {
        assertThat(normalizer.normalizeTasks(PROJECT_ID, null)).isEmpty();
    }

    @Test
    @DisplayName("data 키가 없으면 → 빈 이벤트 목록")
    void normalizeTasks_missingDataKey_returnsEmpty() {
        assertThat(normalizer.normalizeTasks(PROJECT_ID, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("data가 빈 리스트면 → 빈 이벤트 목록")
    void normalizeTasks_emptyDataList_returnsEmpty() {
        assertThat(normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of()))).isEmpty();
    }

    // ─── 기본 필드 매핑 (issue_key/status/priority는 Asana에 개념이 없어 키 자체가 없다) ───

    @Test
    @DisplayName("정상 태스크 → external_id/title/body/created_at 매핑, issue_key·status·priority 키는 존재하지 않는다")
    void normalizeTasks_normalTask_mapsCoreFieldsAndOmitsUnsupportedKeys() {
        Map<String, Object> task = buildTaskNode("11111111-2222-3333-4444-555555555555", "Fix login bug",
                "Users cannot log in on mobile", false, null, null, null);

        List<NormalizedEvent> events = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task)));

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.nodeType()).isEqualTo("Issue");
        assertThat(event.properties())
                .containsEntry("external_id", "11111111-2222-3333-4444-555555555555")
                .containsEntry("title", "Fix login bug")
                .containsEntry("body", "Users cannot log in on mobile")
                .containsEntry("created_at", CREATED_AT)
                .doesNotContainKeys("issue_key", "status", "priority");
    }

    @Test
    @DisplayName("source는 대문자 ASANA로 발행된다 — 소문자로 발행하면 ai-engine의 소스별 그래프 삭제가 " +
            "source 속성을 대문자로 매칭하므로 소문자 이벤트는 삭제 스코프에서 조용히 누락된다")
    void normalizeTasks_source_isUppercaseAsana() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.source()).isEqualTo("ASANA");
    }

    @Test
    @DisplayName("occurredAt은 modified_at을 사용한다")
    void normalizeTasks_occurredAt_usesModifiedAt() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2024-03-15T10:30:00Z"));
    }

    // ─── status_category (completed bool → 소스 중립 3값, Asana는 open/closed 2값만 신호) ───

    @Test
    @DisplayName("completed=true → status_category='closed'")
    void normalizeTasks_completedTrue_statusCategoryClosed() {
        assertStatusCategory(true, "closed");
    }

    @Test
    @DisplayName("completed=false → status_category='open'")
    void normalizeTasks_completedFalse_statusCategoryOpen() {
        assertStatusCategory(false, "open");
    }

    @Test
    @DisplayName("completed 필드 자체가 없으면 방어적으로 status_category='open'")
    void normalizeTasks_missingCompletedField_defensivelyMapsToOpen() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, null, null);
        task.remove("completed");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).containsEntry("status_category", "open");
    }

    private void assertStatusCategory(boolean completed, String expectedCategory) {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", completed, null, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).containsEntry("status_category", expectedCategory);
    }

    // ─── closed_at 3-상태 규약 (closed일 때만 completed_at) ──────────────────────

    @Test
    @DisplayName("completed=true + completed_at 있음 → closed_at = completed_at")
    void normalizeTasks_completedWithCompletedAt_setsClosedAt() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", true, null, null, null);
        task.put("completed_at", "2026-04-06T10:00:00.000Z");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties())
                .containsEntry("status_category", "closed")
                .containsEntry("closed_at", "2026-04-06T10:00:00.000Z");
    }

    @Test
    @DisplayName("closed가 아니면 completed_at이 남아 있어도 closed_at 키 자체가 없다")
    void normalizeTasks_notClosed_noClosedAtKeyEvenWithStaleCompletedAt() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, null, null);
        task.put("completed_at", "2026-04-06T10:00:00.000Z"); // 상태가 되돌아간 뒤 남은 값이라 무시돼야 함

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).containsEntry("status_category", "open");
        assertThat(event.properties()).doesNotContainKey("closed_at");
    }

    // ─── actor (created_by → ActorDto, bot은 항상 null — Asana에 봇 판정 신호 없음) ───

    @Test
    @DisplayName("created_by 있음 → actor는 created_by 정보로 채워지고 bot은 항상 null")
    void normalizeTasks_createdByPresent_actorMapsFieldsWithBotAlwaysNull() {
        Map<String, Object> createdBy = buildPerson("creator-1", "Jane Doe", "jane@example.com");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, createdBy, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.actor().id()).isEqualTo("creator-1");
        assertThat(event.actor().name()).isEqualTo("Jane Doe");
        assertThat(event.actor().email()).isEqualTo("jane@example.com");
        assertThat(event.actor().bot()).isNull();
    }

    @Test
    @DisplayName("created_by 없음(null) → actor 필드 전부 null")
    void normalizeTasks_createdByNull_actorFieldsAllNull() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.actor().id()).isNull();
        assertThat(event.actor().name()).isNull();
        assertThat(event.actor().email()).isNull();
        assertThat(event.actor().bot()).isNull();
    }

    // ─── refs: assignees (Asana는 단일 assignee → 스냅샷 배열, 없으면 빈 배열) ────

    @Test
    @DisplayName("assignee 있음 → refs.assignees에 담당자 1명, bot 키는 없다 — 판정 불가라 미설정")
    void normalizeTasks_assigneePresent_assigneesListHasOneWithoutBotKey() {
        Map<String, Object> assignee = buildPerson("assignee-1", "Assignee Name", "assignee@test.com");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, assignee, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) event.refs().get("assignees");
        assertThat(assignees).hasSize(1);
        assertThat(assignees.get(0))
                .containsEntry("id", "assignee-1")
                .containsEntry("name", "Assignee Name")
                .containsEntry("email", "assignee@test.com")
                .doesNotContainKey("bot");
    }

    @Test
    @DisplayName("assignee 없음(null) → refs.assignees는 빈 배열(키 자체는 존재) — Issue는 스냅샷이라 " +
            "담당자 없음을 명시적 빈 배열로 나타내야 기존 ASSIGNED_TO가 해제된다")
    void normalizeTasks_noAssignee_assigneesListEmptySnapshotSemantics() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.refs()).containsKey("assignees");
        assertThat((List<?>) event.refs().get("assignees")).isEmpty();
    }

    // ─── refs: parent (parentIssueKey는 Asana에 개념이 없어 항상 키 부재) ────────

    @Test
    @DisplayName("parent 있음 → refs.parentExternalId=parent.gid, parentIssueKey 키는 없다")
    void normalizeTasks_parentPresent_parentExternalIdInRefsNoParentIssueKeyKey() {
        Map<String, Object> parent = Map.of("gid", "parent-gid-1");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, null, parent);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.refs()).containsEntry("parentExternalId", "parent-gid-1");
        assertThat(event.refs()).doesNotContainKey("parentIssueKey");
    }

    @Test
    @DisplayName("parent 없음 → refs에 parentExternalId 키가 없다")
    void normalizeTasks_noParent_noParentExternalIdKey() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", false, null, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.refs()).doesNotContainKey("parentExternalId");
    }

    // ─── refs: notes → RefsExtractor(실객체) — 이슈 키·Asana URL 병합 ────────────

    @Test
    @DisplayName("notes에 다른 소스의 이슈 키가 있으면 실제 RefsExtractor를 통해 refs.issueKey로 추출된다")
    void normalizeTasks_notesWithIssueKey_refsExtractedByRealRefsExtractor() {
        Map<String, Object> task = buildTaskNode("id-1", "Title",
                "Blocked by AUTH-42 until fixed", false, null, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.refs()).containsEntry("issueKey", "AUTH-42");
    }

    @Test
    @DisplayName("notes에 Asana 태스크 URL이 있으면 실제 RefsExtractor를 통해 refs.issueExternalRefs로 추출된다")
    void normalizeTasks_notesWithAsanaTaskUrl_refsExtractedByRealRefsExtractor() {
        Map<String, Object> task = buildTaskNode("id-1", "Title",
                "Related: https://app.asana.com/0/1200/98765", false, null, null, null);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issueExternalRefs = (List<Map<String, Object>>) event.refs().get("issueExternalRefs");
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "ASANA")
                .containsEntry("externalId", "98765");
    }

    // ─── 헬퍼 메서드 ───────────────────────────────────────────────────────────

    private Map<String, Object> buildResponseBody(List<Map<String, Object>> tasks) {
        Map<String, Object> body = new HashMap<>();
        body.put("data", tasks);
        return body;
    }

    private Map<String, Object> buildTaskNode(String gid, String name, String notes, Boolean completed,
                                               Map<String, Object> assignee, Map<String, Object> createdBy,
                                               Map<String, Object> parent) {
        Map<String, Object> task = new HashMap<>();
        task.put("gid", gid);
        task.put("name", name);
        task.put("notes", notes);
        task.put("completed", completed);
        task.put("completed_at", null);
        task.put("created_at", CREATED_AT);
        task.put("modified_at", MODIFIED_AT);
        task.put("assignee", assignee);
        task.put("created_by", createdBy);
        task.put("parent", parent);
        return task;
    }

    private Map<String, Object> buildPerson(String gid, String name, String email) {
        Map<String, Object> person = new HashMap<>();
        person.put("gid", gid);
        person.put("name", name);
        person.put("email", email);
        return person;
    }
}
