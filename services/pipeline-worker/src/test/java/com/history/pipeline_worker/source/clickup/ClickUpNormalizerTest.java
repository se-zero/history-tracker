package com.history.pipeline_worker.source.clickup;

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
 * ClickUpNormalizer: ClickUpRawService.ClickUpTaskPage.body()({"tasks": [태스크...]}) →
 * Issue 이벤트 목록 변환. AsanaNormalizerTest 컨벤션(실객체 RefsExtractor 조합)을 미러하되,
 * ClickUp은 status 객체(status/type)·custom_id·priority가 있어 해당 키들이 조건부로 발행되고,
 * 날짜가 epoch ms 문자열이라 ISO-8601로 변환해서 발행한다는 점이 Asana와 다르다.
 */
class ClickUpNormalizerTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CREATED_AT_MS = "1700000000000";
    private static final String CREATED_AT_ISO = "2023-11-14T22:13:20Z";
    private static final String UPDATED_AT_MS = "1700000600000";
    private static final String UPDATED_AT_ISO = "2023-11-14T22:23:20Z";
    private static final String DATE_DONE_MS = "1700001000000";
    private static final String DATE_DONE_ISO = "2023-11-14T22:30:00Z";
    private static final String DATE_CLOSED_MS = "1700002000000";
    private static final String DATE_CLOSED_ISO = "2023-11-14T22:46:40Z";

    private ClickUpNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ClickUpNormalizer(new RefsExtractor());
    }

    // ─── null / 빈 입력 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null 응답 → 빈 이벤트 목록")
    void normalizeTasks_nullResponseBody_returnsEmpty() {
        assertThat(normalizer.normalizeTasks(PROJECT_ID, null)).isEmpty();
    }

    @Test
    @DisplayName("tasks 키가 없으면 → 빈 이벤트 목록")
    void normalizeTasks_missingTasksKey_returnsEmpty() {
        assertThat(normalizer.normalizeTasks(PROJECT_ID, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("tasks가 빈 리스트면 → 빈 이벤트 목록")
    void normalizeTasks_emptyTasksList_returnsEmpty() {
        assertThat(normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of()))).isEmpty();
    }

    @Test
    @DisplayName("id가 null인 태스크는 건너뛴다")
    void normalizeTasks_nullId_taskSkipped() {
        Map<String, Object> task = buildTaskNode(null, "Title", "Body", "open", "open");

        assertThat(normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task)))).isEmpty();
    }

    // ─── 기본 필드 매핑 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 태스크 → external_id/title/body/status/created_at 매핑")
    void normalizeTasks_normalTask_mapsCoreFields() {
        Map<String, Object> task = buildTaskNode("868czp8t3", "로그인 버그 수정", "본문 평문", "in progress", "custom");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.nodeType()).isEqualTo("Issue");
        assertThat(event.properties())
                .containsEntry("external_id", "868czp8t3")
                .containsEntry("title", "로그인 버그 수정")
                .containsEntry("body", "본문 평문")
                .containsEntry("status", "in progress")
                .containsEntry("created_at", CREATED_AT_ISO);
    }

    @Test
    @DisplayName("source는 대문자 CLICKUP으로 발행된다 — 소문자로 발행하면 ai-engine의 소스별 그래프 삭제가 " +
            "source 속성을 대문자로 매칭하므로 소문자 이벤트는 삭제 스코프에서 조용히 누락된다")
    void normalizeTasks_source_isUppercaseClickUp() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.source()).isEqualTo("CLICKUP");
    }

    @Test
    @DisplayName("occurredAt은 date_updated를 epoch ms → Instant로 변환해 사용한다")
    void normalizeTasks_occurredAt_convertsDateUpdatedFromEpochMs() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse(UPDATED_AT_ISO));
    }

    @Test
    @DisplayName("custom_id가 있으면 issue_key로 매핑된다")
    void normalizeTasks_customIdPresent_mapsToIssueKey() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("custom_id", "PROJ-42");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).containsEntry("issue_key", "PROJ-42");
    }

    @Test
    @DisplayName("custom_id가 없으면 issue_key 키 자체가 없다")
    void normalizeTasks_customIdAbsent_noIssueKeyKey() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).doesNotContainKey("issue_key");
    }

    @Test
    @DisplayName("priority.priority가 있으면 priority 속성으로 매핑된다")
    void normalizeTasks_priorityPresent_mapsToPriorityProperty() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("priority", Map.of("id", "2", "priority", "high", "color", "#x"));

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).containsEntry("priority", "high");
    }

    @Test
    @DisplayName("priority가 없으면 priority 키 자체가 없다")
    void normalizeTasks_priorityAbsent_noPriorityKey() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).doesNotContainKey("priority");
    }

    // ─── status_category (status.type → 소스 중립 값) ────────────────────────────

    @Test
    @DisplayName("status.type=open → status_category='open'")
    void normalizeTasks_statusTypeOpen_statusCategoryOpen() {
        assertStatusCategory("open", "open");
    }

    @Test
    @DisplayName("status.type=custom → status_category='in_progress'")
    void normalizeTasks_statusTypeCustom_statusCategoryInProgress() {
        assertStatusCategory("custom", "in_progress");
    }

    @Test
    @DisplayName("status.type=done → status_category='closed'")
    void normalizeTasks_statusTypeDone_statusCategoryClosed() {
        assertStatusCategory("done", "closed");
    }

    @Test
    @DisplayName("status.type=closed → status_category='closed'")
    void normalizeTasks_statusTypeClosed_statusCategoryClosed() {
        assertStatusCategory("closed", "closed");
    }

    @Test
    @DisplayName("status.type이 알 수 없는 값이면 방어적으로 status_category='open'")
    void normalizeTasks_unknownStatusType_defensivelyMapsToOpen() {
        assertStatusCategory("unknown-type", "open");
    }

    @Test
    @DisplayName("status 필드 자체가 없으면 방어적으로 status_category='open'")
    void normalizeTasks_missingStatusField_defensivelyMapsToOpen() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.remove("status");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).containsEntry("status_category", "open");
    }

    private void assertStatusCategory(String statusType, String expectedCategory) {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "status-text", statusType);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).containsEntry("status_category", expectedCategory);
    }

    // ─── closed_at 3-상태 (closed일 때만 date_closed ?? date_done) ───────────────

    @Test
    @DisplayName("done 태스크(date_done만 있음) → closed_at=date_done의 ISO 변환값")
    void normalizeTasks_doneStatusWithDateDoneOnly_setsClosedAtFromDateDone() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "complete", "done");
        task.put("date_done", DATE_DONE_MS);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties())
                .containsEntry("status_category", "closed")
                .containsEntry("closed_at", DATE_DONE_ISO);
    }

    @Test
    @DisplayName("closed 태스크(date_closed·date_done 둘 다 있음) → closed_at은 date_closed를 우선한다")
    void normalizeTasks_closedStatusWithBothDates_prefersDateClosed() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "closed", "closed");
        task.put("date_closed", DATE_CLOSED_MS);
        task.put("date_done", DATE_DONE_MS);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties())
                .containsEntry("status_category", "closed")
                .containsEntry("closed_at", DATE_CLOSED_ISO);
    }

    @Test
    @DisplayName("open/custom 태스크는 date_done 값이 남아 있어도 closed_at 키 자체가 없다")
    void normalizeTasks_notClosed_noClosedAtKeyEvenWithStaleDateDone() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("date_done", DATE_DONE_MS); // 상태가 되돌아간 뒤 남은 값이라 무시돼야 함

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.properties()).containsEntry("status_category", "open");
        assertThat(event.properties()).doesNotContainKey("closed_at");
    }

    // ─── actor (creator → ActorDto, bot은 username이 "ClickBot"일 때만 true) ─────

    @Test
    @DisplayName("creator 있음(일반 사용자) → actor 필드 매핑, bot은 null")
    void normalizeTasks_creatorPresent_actorMapsFieldsWithBotNull() {
        Map<String, Object> creator = buildPerson(183, "Jerry", "j@example.com");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("creator", creator);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.actor().id()).isEqualTo("183");
        assertThat(event.actor().name()).isEqualTo("Jerry");
        assertThat(event.actor().email()).isEqualTo("j@example.com");
        assertThat(event.actor().bot()).isNull();
    }

    @Test
    @DisplayName("creator의 username이 ClickBot이면 actor.bot=true")
    void normalizeTasks_creatorIsClickBot_actorBotTrue() {
        Map<String, Object> creator = buildPerson(999, "ClickBot", "bot@clickup.com");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("creator", creator);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.actor().bot()).isTrue();
    }

    @Test
    @DisplayName("creator 없음(null) → actor 필드 전부 null")
    void normalizeTasks_creatorNull_actorFieldsAllNull() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.actor().id()).isNull();
        assertThat(event.actor().name()).isNull();
        assertThat(event.actor().email()).isNull();
        assertThat(event.actor().bot()).isNull();
    }

    @Test
    @DisplayName("creator의 id가 null이면 actor.id는 문자열 \"null\"이 아니라 실제 null이어야 한다 — " +
            "String.valueOf(null)로 변환하면 진짜 null과 구분되지 않는 오염된 값이 발행된다")
    void normalizeTasks_creatorIdNull_actorIdIsRealNullNotStringLiteral() {
        Map<String, Object> creator = new HashMap<>();
        creator.put("id", null);
        creator.put("username", "Jerry");
        creator.put("email", "j@example.com");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("creator", creator);

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.actor().id()).isNull();
    }

    // ─── refs: assignees (정수 id → String 변환, 담당자 배열 스냅샷) ──────────────

    @Test
    @DisplayName("assignees 있음 → refs.assignees에 id가 String으로 변환된 담당자가 담긴다, bot 키는 없다")
    void normalizeTasks_assigneesPresent_idsConvertedToStringWithoutBotKey() {
        Map<String, Object> assignee = buildPerson(184, "Kim", "k@example.com");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("assignees", List.of(assignee));

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) event.refs().get("assignees");
        assertThat(assignees).hasSize(1);
        assertThat(assignees.get(0))
                .containsEntry("id", "184")
                .containsEntry("name", "Kim")
                .containsEntry("email", "k@example.com")
                .doesNotContainKey("bot");
    }

    @Test
    @DisplayName("담당자의 username이 ClickBot이면 refs.assignees 원소에 bot=true 키가 추가된다")
    void normalizeTasks_clickBotAssignee_assigneeEntryHasBotTrueKey() {
        Map<String, Object> assignee = buildPerson(999, "ClickBot", "bot@clickup.com");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("assignees", List.of(assignee));

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) event.refs().get("assignees");
        assertThat(assignees.get(0)).containsEntry("bot", true);
    }

    @Test
    @DisplayName("assignees 필드가 없으면(null) → refs.assignees는 빈 배열(키는 존재) — Issue는 스냅샷이라 " +
            "담당자 없음을 명시적 빈 배열로 나타내야 기존 ASSIGNED_TO가 해제된다")
    void normalizeTasks_missingAssignees_assigneesListEmptySnapshotSemantics() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.refs()).containsKey("assignees");
        assertThat((List<?>) event.refs().get("assignees")).isEmpty();
    }

    @Test
    @DisplayName("assignees가 빈 배열이면 → refs.assignees도 빈 배열이다")
    void normalizeTasks_emptyAssigneesArray_assigneesListEmpty() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("assignees", List.of());

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat((List<?>) event.refs().get("assignees")).isEmpty();
    }

    @Test
    @DisplayName("assignees 항목의 id가 null이면 그 항목은 refs.assignees에서 제외된다 — " +
            "String.valueOf(null)로 변환하면 문자열 \"null\"이 유효한 id처럼 들어간다")
    void normalizeTasks_assigneeIdNull_excludedFromAssigneesList() {
        Map<String, Object> assignee = new HashMap<>();
        assignee.put("id", null);
        assignee.put("username", "Kim");
        assignee.put("email", "k@example.com");
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("assignees", List.of(assignee));

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat((List<?>) event.refs().get("assignees")).isEmpty();
    }

    // ─── refs: parent ────────────────────────────────────────────────────────

    @Test
    @DisplayName("parent 있음 → refs.parentExternalId=parent 값")
    void normalizeTasks_parentPresent_parentExternalIdInRefs() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");
        task.put("parent", "parent-task-id");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.refs()).containsEntry("parentExternalId", "parent-task-id");
    }

    @Test
    @DisplayName("parent 없음 → refs에 parentExternalId 키가 없다")
    void normalizeTasks_noParent_noParentExternalIdKey() {
        Map<String, Object> task = buildTaskNode("id-1", "Title", "Body", "open", "open");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        assertThat(event.refs()).doesNotContainKey("parentExternalId");
    }

    // ─── refs: text_content → RefsExtractor(실객체) — ClickUp URL 추출 ───────────

    @Test
    @DisplayName("text_content에 ClickUp 태스크 URL이 있으면 실제 RefsExtractor를 통해 refs.issueExternalRefs로 추출된다")
    void normalizeTasks_textContentWithClickUpTaskUrl_refsExtractedByRealRefsExtractor() {
        Map<String, Object> task = buildTaskNode("id-1", "Title",
                "Related: https://app.clickup.com/t/868czp8t3", "open", "open");

        NormalizedEvent event = normalizer.normalizeTasks(PROJECT_ID, buildResponseBody(List.of(task))).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issueExternalRefs = (List<Map<String, Object>>) event.refs().get("issueExternalRefs");
        assertThat(issueExternalRefs).hasSize(1);
        assertThat(issueExternalRefs.get(0))
                .containsEntry("source", "CLICKUP")
                .containsEntry("externalId", "868czp8t3");
    }

    // ─── 헬퍼 메서드 ───────────────────────────────────────────────────────────

    private Map<String, Object> buildResponseBody(List<Map<String, Object>> tasks) {
        Map<String, Object> body = new HashMap<>();
        body.put("tasks", tasks);
        return body;
    }

    private Map<String, Object> buildTaskNode(String id, String name, String textContent,
                                               String statusText, String statusType) {
        Map<String, Object> task = new HashMap<>();
        task.put("id", id);
        task.put("custom_id", null);
        task.put("name", name);
        task.put("text_content", textContent);
        task.put("status", buildStatus(statusText, statusType));
        task.put("date_created", CREATED_AT_MS);
        task.put("date_updated", UPDATED_AT_MS);
        task.put("date_closed", null);
        task.put("date_done", null);
        task.put("creator", null);
        task.put("assignees", null);
        task.put("parent", null);
        task.put("priority", null);
        return task;
    }

    private Map<String, Object> buildStatus(String status, String type) {
        Map<String, Object> statusMap = new HashMap<>();
        statusMap.put("status", status);
        statusMap.put("type", type);
        return statusMap;
    }

    private Map<String, Object> buildPerson(int id, String username, String email) {
        Map<String, Object> person = new HashMap<>();
        person.put("id", id);
        person.put("username", username);
        person.put("email", email);
        return person;
    }
}
