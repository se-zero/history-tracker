package com.history.pipeline_worker.source.linear;

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
 * LinearNormalizer: Linear GraphQL team.issues 결과(LinearRawService.LinearIssuePage.searchResult() —
 * {"issues": [노드...]}) → Issue 이벤트 목록 변환. JiraNormalizerTest 컨벤션(실객체 RefsExtractor
 * 조합)을 미러하되, Linear의 issue 노드는 Jira의 "fields" 래핑 없이 평평한 구조라는 점이 다르다.
 */
class LinearNormalizerTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CREATED_AT = "2024-03-01T00:00:00.000Z";
    private static final String UPDATED_AT = "2024-03-15T10:30:00.000Z";

    private LinearNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new LinearNormalizer(new RefsExtractor());
    }

    // ─── null / 빈 입력 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("null 입력 → 빈 이벤트 목록")
    void normalizeIssues_nullInput_returnsEmpty() {
        assertThat(normalizer.normalizeIssues(PROJECT_ID, null)).isEmpty();
    }

    @Test
    @DisplayName("issues 빈 리스트 → 빈 이벤트 목록")
    void normalizeIssues_emptyIssuesList_returnsEmpty() {
        assertThat(normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of()))).isEmpty();
    }

    // ─── 기본 필드 매핑 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 이슈 → Issue 이벤트 생성, external_id/issue_key/title/body/status 매핑")
    void normalizeIssues_normalIssue_mapsCoreFields() {
        Map<String, Object> issue = buildIssueNode("11111111-2222-3333-4444-555555555555", "ENG-1",
                "Fix login bug", "Users cannot log in on mobile", "In Review", "started", null, null, null, null);

        List<NormalizedEvent> events = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue)));

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.nodeType()).isEqualTo("Issue");
        assertThat(event.properties())
                .containsEntry("external_id", "11111111-2222-3333-4444-555555555555")
                .containsEntry("issue_key", "ENG-1")
                .containsEntry("title", "Fix login bug")
                .containsEntry("body", "Users cannot log in on mobile")
                .containsEntry("status", "In Review");
    }

    @Test
    @DisplayName("source는 대문자 LINEAR로 발행된다 — 소문자로 발행하면 ai-engine의 소스별 그래프 삭제가 " +
            "source 속성을 대문자로 매칭하므로 소문자 이벤트는 삭제 스코프에서 조용히 누락된다")
    void normalizeIssues_source_isUppercaseLinear() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-2", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.source()).isEqualTo("LINEAR");
    }

    @Test
    @DisplayName("occurredAt은 updatedAt을 사용한다")
    void normalizeIssues_occurredAt_usesUpdatedAt() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-3", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2024-03-15T10:30:00Z"));
    }

    @Test
    @DisplayName("updatedAt=null → occurredAt은 createdAt 파싱값으로 폴백한다")
    void normalizeIssues_updatedAtNull_occurredAtFallsBackToCreatedAt() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-4", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);
        issue.put("updatedAt", null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.occurredAt()).isEqualTo(Instant.parse(CREATED_AT));
    }

    @Test
    @DisplayName("updatedAt·createdAt 둘 다 null이어도 예외 없이 이벤트가 생성되고 occurredAt은 null이 아니다")
    void normalizeIssues_updatedAtAndCreatedAtBothNull_eventCreatedWithNonNullOccurredAt() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-5", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);
        issue.put("updatedAt", null);
        issue.put("createdAt", null);

        List<NormalizedEvent> events = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue)));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).occurredAt()).isNotNull();
    }

    // ─── status_category 매핑 (state.type → 소스 중립 3값) ───────────────────────

    @Test
    @DisplayName("state.type='backlog' → status_category='open'")
    void normalizeIssues_stateTypeBacklog_mapsToOpen() {
        assertStatusCategory("backlog", "open");
    }

    @Test
    @DisplayName("state.type='unstarted' → status_category='open'")
    void normalizeIssues_stateTypeUnstarted_mapsToOpen() {
        assertStatusCategory("unstarted", "open");
    }

    @Test
    @DisplayName("state.type='started' → status_category='in_progress'")
    void normalizeIssues_stateTypeStarted_mapsToInProgress() {
        assertStatusCategory("started", "in_progress");
    }

    @Test
    @DisplayName("state.type='completed' → status_category='closed'")
    void normalizeIssues_stateTypeCompleted_mapsToClosed() {
        assertStatusCategory("completed", "closed");
    }

    @Test
    @DisplayName("state.type='canceled' → status_category='closed'")
    void normalizeIssues_stateTypeCanceled_mapsToClosed() {
        assertStatusCategory("canceled", "closed");
    }

    @Test
    @DisplayName("알 수 없는 state.type → 방어적으로 status_category='open'")
    void normalizeIssues_unknownStateType_defaultsToOpen() {
        assertStatusCategory("triage", "open");
    }

    // ─── priority (priorityLabel 원문 발행, 0=No priority는 생략) ────────────────

    @Test
    @DisplayName("priority가 0이 아니면 priorityLabel 원문을 properties.priority로 발행한다 — 숫자 매핑 없이 Linear 라벨 그대로")
    void normalizeIssues_nonZeroPriority_emitsPriorityLabel() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-30", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.properties()).containsEntry("priority", "High");
    }

    @Test
    @DisplayName("priority=0(No priority)은 우선순위 미설정이므로 priority 키를 생략한다 — Jira의 우선순위 없음과 동일 취급")
    void normalizeIssues_zeroPriority_omitsPriorityKey() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-31", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);
        issue.put("priority", 0);
        issue.put("priorityLabel", "No priority");

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.properties()).doesNotContainKey("priority");
    }

    private void assertStatusCategory(String stateType, String expectedCategory) {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-9", "Title", "Body", "Custom", stateType,
                null, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.properties()).containsEntry("status_category", expectedCategory);
    }

    // ─── closed_at 3-상태 규약 (closed일 때만 completedAt ?? canceledAt) ─────────

    @Test
    @DisplayName("completed + completedAt 있음 → closed_at = completedAt")
    void normalizeIssues_completedWithCompletedAt_setsClosedAt() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-10", "Title", "Body", "Done", "completed",
                null, null, null, null);
        issue.put("completedAt", "2026-04-06T10:00:00.000Z");
        issue.put("canceledAt", null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.properties())
                .containsEntry("status_category", "closed")
                .containsEntry("closed_at", "2026-04-06T10:00:00.000Z");
    }

    @Test
    @DisplayName("canceled + completedAt 없음 → closed_at은 canceledAt으로 폴백")
    void normalizeIssues_canceledWithoutCompletedAt_fallsBackToCanceledAt() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-11", "Title", "Body", "Canceled", "canceled",
                null, null, null, null);
        issue.put("completedAt", null);
        issue.put("canceledAt", "2026-04-07T09:00:00.000Z");

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.properties())
                .containsEntry("status_category", "closed")
                .containsEntry("closed_at", "2026-04-07T09:00:00.000Z");
    }

    @Test
    @DisplayName("closed가 아니면 completedAt/canceledAt이 남아 있어도 closed_at 키 자체가 없다")
    void normalizeIssues_notClosed_noClosedAtKeyEvenWithStaleTimestamps() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-12", "Title", "Body", "In Progress", "started",
                null, null, null, null);
        issue.put("completedAt", "2026-04-06T10:00:00.000Z"); // 상태가 되돌아간 뒤 남은 값이라 무시돼야 함
        issue.put("canceledAt", null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.properties()).containsEntry("status_category", "in_progress");
        assertThat(event.properties()).doesNotContainKey("closed_at");
    }

    // ─── actor 봇 판정 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("botActor 존재 → actor는 botActor 정보로 채워지고 bot=true")
    void normalizeIssues_botActorPresent_actorIsBotActorWithBotTrue() {
        Map<String, Object> creator = buildPerson("human-1", "Human Creator", "human@example.com", false);
        Map<String, Object> botActor = buildBotActor("bot-1", "GitHub Sync", "workflow");
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-13", "Title", "Body", "Todo", "unstarted",
                null, creator, botActor, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.actor().id()).isEqualTo("bot-1");
        assertThat(event.actor().name()).isEqualTo("GitHub Sync");
        assertThat(event.actor().bot()).isTrue();
    }

    @Test
    @DisplayName("botActor 없고 creator 있음 → actor는 creator 정보로 채워지고 bot은 creator.app을 그대로 반영")
    void normalizeIssues_noBotActorCreatorPresent_actorBotReflectsCreatorApp() {
        Map<String, Object> creator = buildPerson("user-1", "Jane Doe", "jane@example.com", true);
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-14", "Title", "Body", "Todo", "unstarted",
                null, creator, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.actor().id()).isEqualTo("user-1");
        assertThat(event.actor().name()).isEqualTo("Jane Doe");
        assertThat(event.actor().email()).isEqualTo("jane@example.com");
        assertThat(event.actor().bot()).isTrue();
    }

    @Test
    @DisplayName("botActor·creator 모두 없음 → JiraNormalizer의 작성자 부재 관례를 미러해 actor 필드 전부 null")
    void normalizeIssues_noBotActorNoCreator_actorFieldsAllNull() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-15", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.actor().id()).isNull();
        assertThat(event.actor().name()).isNull();
        assertThat(event.actor().email()).isNull();
        assertThat(event.actor().bot()).isNull();
    }

    // ─── refs: assignees (Linear는 단일 assignee → 스냅샷 배열, 없으면 빈 배열) ────

    @Test
    @DisplayName("assignee 있음 → refs.assignees에 담당자 1명, bot은 assignee.app을 반영")
    void normalizeIssues_assigneePresent_assigneesListHasOneWithBotFromApp() {
        Map<String, Object> assignee = buildPerson("assignee-1", "Assignee Name", "assignee@test.com", false);
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-16", "Title", "Body", "Todo", "unstarted",
                assignee, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignees = (List<Map<String, Object>>) event.refs().get("assignees");
        assertThat(assignees).hasSize(1);
        assertThat(assignees.get(0))
                .containsEntry("id", "assignee-1")
                .containsEntry("name", "Assignee Name")
                .containsEntry("email", "assignee@test.com")
                .containsEntry("bot", false);
    }

    @Test
    @DisplayName("assignee 없음 → refs.assignees는 빈 배열(키 자체는 존재) — Issue는 스냅샷이라 " +
            "담당자 없음을 명시적 빈 배열로 나타내야 기존 ASSIGNED_TO가 해제된다")
    void normalizeIssues_noAssignee_assigneesListEmptySnapshotSemantics() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-17", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.refs()).containsKey("assignees");
        assertThat((List<?>) event.refs().get("assignees")).isEmpty();
    }

    @Test
    @DisplayName("assignee의 id가 null이면 refs.assignees는 그 담당자를 제외한 빈 배열이어야 한다 — " +
            "id 없는 항목을 그대로 넣으면 해제(unassign) 규약이 깨진다")
    void normalizeIssues_assigneeIdNull_excludedFromAssigneesList() {
        Map<String, Object> assignee = buildPerson(null, "Assignee Name", "assignee@test.com", false);
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-21", "Title", "Body", "Todo", "unstarted",
                assignee, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.refs()).containsKey("assignees");
        assertThat((List<?>) event.refs().get("assignees")).isEmpty();
    }

    // ─── refs: parent ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("parent 있음 → refs에 parentExternalId(parent.id)·parentIssueKey(parent.identifier) 포함")
    void normalizeIssues_parentPresent_parentKeysInRefs() {
        Map<String, Object> parent = Map.of("id", "parent-uuid-1", "identifier", "ENG-1");
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-18", "Title", "Body", "Todo", "unstarted",
                null, null, null, parent);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.refs())
                .containsEntry("parentExternalId", "parent-uuid-1")
                .containsEntry("parentIssueKey", "ENG-1");
    }

    @Test
    @DisplayName("parent 없음 → refs에 parentExternalId·parentIssueKey 모두 없음")
    void normalizeIssues_noParent_noParentKeysInRefs() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-19", "Title", "Body", "Todo", "unstarted",
                null, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.refs()).doesNotContainKeys("parentExternalId", "parentIssueKey");
    }

    // ─── refs: description → RefsExtractor(실객체) ────────────────────────────────

    @Test
    @DisplayName("description에 이슈 키가 있으면 실제 RefsExtractor를 통해 refs.issueKey로 추출된다")
    void normalizeIssues_descriptionWithIssueKey_refsExtractedByRealRefsExtractor() {
        Map<String, Object> issue = buildIssueNode("id-1", "ENG-20", "Title",
                "Blocked by AUTH-42 until fixed", "Todo", "unstarted", null, null, null, null);

        NormalizedEvent event = normalizer.normalizeIssues(PROJECT_ID, buildSearchResult(List.of(issue))).get(0);

        assertThat(event.refs()).containsEntry("issueKey", "AUTH-42");
    }

    // ─── 헬퍼 메서드 ───────────────────────────────────────────────────────────

    private Map<String, Object> buildSearchResult(List<Map<String, Object>> issues) {
        Map<String, Object> result = new HashMap<>();
        result.put("issues", issues);
        return result;
    }

    private Map<String, Object> buildIssueNode(String id, String identifier, String title, String description,
                                                String stateName, String stateType,
                                                Map<String, Object> assignee, Map<String, Object> creator,
                                                Map<String, Object> botActor, Map<String, Object> parent) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("identifier", identifier);
        node.put("title", title);
        node.put("description", description);
        node.put("priority", 2);
        node.put("priorityLabel", "High");
        node.put("url", "https://linear.app/acme/issue/" + identifier + "/slug");
        node.put("createdAt", CREATED_AT);
        node.put("updatedAt", UPDATED_AT);
        node.put("completedAt", null);
        node.put("canceledAt", null);
        node.put("state", buildState(stateName, stateType));
        node.put("assignee", assignee);
        node.put("creator", creator);
        node.put("botActor", botActor);
        node.put("labels", Map.of("nodes", List.of()));
        node.put("parent", parent);
        node.put("team", Map.of("id", "team-1"));
        return node;
    }

    private Map<String, Object> buildState(String name, String type) {
        Map<String, Object> state = new HashMap<>();
        state.put("name", name);
        state.put("type", type);
        return state;
    }

    private Map<String, Object> buildPerson(String id, String name, String email, Boolean app) {
        Map<String, Object> person = new HashMap<>();
        person.put("id", id);
        person.put("name", name);
        person.put("email", email);
        person.put("app", app);
        return person;
    }

    private Map<String, Object> buildBotActor(String id, String name, String type) {
        Map<String, Object> bot = new HashMap<>();
        bot.put("id", id);
        bot.put("name", name);
        bot.put("type", type);
        return bot;
    }
}
