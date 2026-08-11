package com.history.pipeline_worker.source.jira;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import com.history.pipeline_worker.util.JiraDateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JiraNormalizer {

    private final RefsExtractor refsExtractor;

    // Jira search 결과의 issues 배열 → Issue 이벤트 목록
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeIssues(String projectId, Map<String, Object> searchResult) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (searchResult == null) return events;

        List<Map<String, Object>> issues = (List<Map<String, Object>>) searchResult.get("issues");
        if (issues == null) return events;

        for (Map<String, Object> issue : issues) {
            Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
            if (fields == null) continue;

            // 작성자: Jira에서 reporter 또는 creator
            Map<String, Object> reporter = (Map<String, Object>) fields.get("reporter");
            Map<String, Object> assigneeField = (Map<String, Object>) fields.get("assignee");
            Map<String, Object> status = (Map<String, Object>) fields.get("status");
            Map<String, Object> issueType = (Map<String, Object>) fields.get("issuetype");
            Map<String, Object> priority = (Map<String, Object>) fields.get("priority");
            Map<String, Object> parent = (Map<String, Object>) fields.get("parent");
            String parentKey = parent != null ? (String) parent.get("key") : null;
            String parentExternalId = parent != null ? (String) parent.get("id") : null;

            String createdAt = (String) fields.get("created");
            String updatedAt = (String) fields.get("updated");
            String resolutionDate = (String) fields.get("resolutiondate");
            String summary = (String) fields.get("summary");
            Object description = fields.get("description");
            String descriptionText = extractPlainText(description);

            String statusName = status != null ? (String) status.get("name") : null;
            String statusCategory = mapStatusCategory(extractStatusCategoryKey(status));
            String closedAt = "closed".equals(statusCategory)
                    ? (resolutionDate != null ? resolutionDate : updatedAt)
                    : null;

            Map<String, Object> properties = new HashMap<>();
            properties.put("external_id", issue.get("id"));
            properties.put("issue_key", issue.get("key"));
            properties.put("title", summary);
            properties.put("body", descriptionText);
            properties.put("status", statusName);
            properties.put("status_category", statusCategory);
            properties.put("issue_type", issueType != null ? issueType.get("name") : null);
            properties.put("priority", priority != null ? priority.get("name") : null);
            properties.put("created_at", createdAt);
            // 종료된 이슈만 closed_at을 채운다.
            // 미종료(재오픈 포함) 상태에서는 키를 넣지 않으면 ai-engine builder가 status_category를
            // 보고 i.closedAt을 null로 클리어한다 (status-aware Cypher).
            if (closedAt != null) {
                properties.put("closed_at", closedAt);
            }

            ActorDto actor = reporter != null
                    ? new ActorDto(
                            (String) reporter.get("accountId"),
                            (String) reporter.get("displayName"),
                            (String) reporter.get("emailAddress"))
                    : new ActorDto(null, null, null);

            Map<String, Object> refs = new HashMap<>(refsExtractor.extract(summary + " " + descriptionText));
            if (parentKey != null) refs.put("parentIssueKey", parentKey);
            if (parentExternalId != null) refs.put("parentExternalId", parentExternalId);
            if (assigneeField != null) {
                String assigneeId = (String) assigneeField.get("accountId");
                if (assigneeId != null) {
                    Map<String, Object> assignee = new HashMap<>();
                    assignee.put("id", assigneeId);
                    assignee.put("name", assigneeField.get("displayName"));
                    assignee.put("email", assigneeField.get("emailAddress"));
                    refs.put("assignees", List.of(assignee));
                }
            }

            events.add(new NormalizedEvent(
                    projectId,
                    "Issue",
                    "JIRA",
                    resolveOccurredAt(updatedAt, createdAt),
                    actor,
                    properties,
                    refs
            ));
        }
        return events;
    }

    private Instant resolveOccurredAt(String updatedAt, String createdAt) {
        if (updatedAt != null) return JiraDateUtils.parse(updatedAt);
        if (createdAt != null) return JiraDateUtils.parse(createdAt);
        return Instant.now();
    }

    @SuppressWarnings("unchecked")
    private String extractStatusCategoryKey(Map<String, Object> status) {
        if (status == null) return null;
        Map<String, Object> statusCategory = (Map<String, Object>) status.get("statusCategory");
        return statusCategory != null ? (String) statusCategory.get("key") : null;
    }

    // Jira statusCategory.key는 new/indeterminate/done 세 값만 정의돼 있으나,
    // 커스텀 워크플로우 등 예상 밖 값이 오면 진행 중이 아닌 open으로 방어한다.
    private String mapStatusCategory(String statusCategoryKey) {
        if ("new".equals(statusCategoryKey)) return "open";
        if ("indeterminate".equals(statusCategoryKey)) return "in_progress";
        if ("done".equals(statusCategoryKey)) return "closed";
        return "open";
    }

    @SuppressWarnings("unchecked")
    private String extractPlainText(Object node) {
        if (node == null) return "";
        if (node instanceof String) return (String) node;
        if (!(node instanceof Map)) return "";

        Map<String, Object> map = (Map<String, Object>) node;

        if ("text".equals(map.get("type")) && map.containsKey("text")) {
            return (String) map.get("text");
        }

        Object content = map.get("content");
        if (content instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object child : (List<?>) content) {
                String childText = extractPlainText(child);
                if (!childText.isEmpty()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(childText);
                }
            }
            return sb.toString();
        }

        return "";
    }
}
