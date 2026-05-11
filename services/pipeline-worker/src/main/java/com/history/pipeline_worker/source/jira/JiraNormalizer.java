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
    public List<NormalizedEvent> normalizeIssues(Map<String, Object> searchResult) {
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

            String createdAt = (String) fields.get("created");
            String updatedAt = (String) fields.get("updated");
            String summary = (String) fields.get("summary");
            Object description = fields.get("description");
            String descriptionText = extractPlainText(description);

            Map<String, Object> properties = new HashMap<>();
            properties.put("jira_key", issue.get("key"));
            properties.put("title", summary);
            properties.put("body", descriptionText);
            properties.put("status", status != null ? status.get("name") : null);
            properties.put("issue_type", issueType != null ? issueType.get("name") : null);
            properties.put("priority", priority != null ? priority.get("name") : null);
            properties.put("assignee", assigneeField != null ? assigneeField.get("displayName") : null);
            properties.put("created_at", createdAt);

            ActorDto actor = reporter != null
                    ? new ActorDto(
                            (String) reporter.get("accountId"),
                            (String) reporter.get("displayName"),
                            (String) reporter.get("emailAddress"))
                    : new ActorDto(null, null, null);

            Map<String, String> refs = new HashMap<>(refsExtractor.extract(summary + " " + descriptionText));
            if (parentKey != null) refs.put("parentJiraKey", parentKey);
            if (assigneeField != null) refs.put("assigneeId", (String) assigneeField.get("accountId"));

            events.add(new NormalizedEvent(
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
