package com.history.pipeline_worker.source.linear;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class LinearNormalizer {

    private final RefsExtractor refsExtractor;

    // LinearRawService.LinearIssuePage.searchResult()({"issues": [노드...]}) → Issue 이벤트 목록.
    // Jira와 달리 "fields" 래핑이 없는 평평한 노드 구조다.
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeIssues(String projectId, Map<String, Object> searchResult) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (searchResult == null) return events;

        List<Map<String, Object>> issues = (List<Map<String, Object>>) searchResult.get("issues");
        if (issues == null) return events;

        for (Map<String, Object> issue : issues) {
            Map<String, Object> state = (Map<String, Object>) issue.get("state");
            Map<String, Object> assignee = (Map<String, Object>) issue.get("assignee");
            Map<String, Object> creator = (Map<String, Object>) issue.get("creator");
            Map<String, Object> botActor = (Map<String, Object>) issue.get("botActor");
            Map<String, Object> parent = (Map<String, Object>) issue.get("parent");

            String title = (String) issue.get("title");
            String description = (String) issue.get("description");
            String createdAt = (String) issue.get("createdAt");
            String updatedAt = (String) issue.get("updatedAt");

            String statusName = state != null ? (String) state.get("name") : null;
            String statusCategory = mapStatusCategory(state != null ? (String) state.get("type") : null);
            String closedAt = "closed".equals(statusCategory)
                    ? firstNonNull((String) issue.get("completedAt"), (String) issue.get("canceledAt"))
                    : null;

            Map<String, Object> properties = new HashMap<>();
            properties.put("external_id", issue.get("id"));
            properties.put("issue_key", issue.get("identifier"));
            properties.put("title", title);
            properties.put("body", description);
            properties.put("status", statusName);
            properties.put("status_category", statusCategory);
            properties.put("created_at", createdAt);
            // priority=0은 "No priority"(미설정) — 키를 생략해 우선순위 없는 Jira 이슈와 동일 취급.
            // 라벨은 숫자 매핑 없이 Linear가 주는 priorityLabel 원문을 쓴다.
            String priorityLabel = (String) issue.get("priorityLabel");
            if (issue.get("priority") instanceof Number priority && priority.doubleValue() != 0 && priorityLabel != null) {
                properties.put("priority", priorityLabel);
            }
            // 종료된 이슈만 closed_at을 채운다 — 미종료(재오픈 포함)에서 키를 생략해야
            // ai-engine builder가 status_category를 보고 closedAt을 null로 클리어한다.
            if (closedAt != null) {
                properties.put("closed_at", closedAt);
            }

            events.add(new NormalizedEvent(
                    projectId,
                    "Issue",
                    "LINEAR",
                    resolveOccurredAt(updatedAt, createdAt),
                    resolveActor(botActor, creator),
                    properties,
                    resolveRefs(description, assignee, parent)
            ));
        }
        return events;
    }

    // Linear 타임스탬프는 ISO-8601이라 Instant.parse 그대로 사용한다.
    // updatedAt → createdAt → now 순으로 폴백한다(JiraNormalizer.resolveOccurredAt 미러).
    private Instant resolveOccurredAt(String updatedAt, String createdAt) {
        if (updatedAt != null) return Instant.parse(updatedAt);
        if (createdAt != null) return Instant.parse(createdAt);
        return Instant.now();
    }

    // botActor(워크플로 자동화가 만든 이슈) 우선, 없으면 creator, 둘 다 없으면 작성자 부재로 전부 null.
    private ActorDto resolveActor(Map<String, Object> botActor, Map<String, Object> creator) {
        if (botActor != null) {
            return new ActorDto((String) botActor.get("id"), (String) botActor.get("name"), null, true);
        }
        if (creator != null) {
            return new ActorDto(
                    (String) creator.get("id"),
                    (String) creator.get("name"),
                    (String) creator.get("email"),
                    (Boolean) creator.get("app"));
        }
        return new ActorDto(null, null, null, null);
    }

    private Map<String, Object> resolveRefs(String description, Map<String, Object> assignee, Map<String, Object> parent) {
        Map<String, Object> refs = new HashMap<>(refsExtractor.extract(description));

        // Issue는 최신 스냅샷이라 담당자 없음도 명시적 빈 배열로 나타내야 기존 ASSIGNED_TO가 해제된다.
        List<Map<String, Object>> assignees = new ArrayList<>();
        if (assignee != null && assignee.get("id") != null) {
            Map<String, Object> assigneeEntry = new HashMap<>();
            assigneeEntry.put("id", assignee.get("id"));
            assigneeEntry.put("name", assignee.get("name"));
            assigneeEntry.put("email", assignee.get("email"));
            assigneeEntry.put("bot", assignee.get("app"));
            assignees.add(assigneeEntry);
        }
        refs.put("assignees", assignees);

        if (parent != null) {
            refs.put("parentExternalId", parent.get("id"));
            refs.put("parentIssueKey", parent.get("identifier"));
        }

        return refs;
    }

    // Linear state.type은 backlog/unstarted/started/completed/canceled 다섯 값이 정의돼 있으나,
    // 예상 밖 값이 오면 진행 중이 아닌 open으로 방어한다.
    private String mapStatusCategory(String stateType) {
        if ("backlog".equals(stateType) || "unstarted".equals(stateType)) return "open";
        if ("started".equals(stateType)) return "in_progress";
        if ("completed".equals(stateType) || "canceled".equals(stateType)) return "closed";
        return "open";
    }

    private String firstNonNull(String primary, String fallback) {
        return primary != null ? primary : fallback;
    }
}
