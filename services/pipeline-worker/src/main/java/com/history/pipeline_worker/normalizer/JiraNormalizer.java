package com.history.pipeline_worker.normalizer;

import com.history.pipeline_worker.dto.ActorDto;
import com.history.pipeline_worker.dto.NormalizedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JiraNormalizer {

    private static final DateTimeFormatter JIRA_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

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

            String createdAt = (String) fields.get("created");
            String summary = (String) fields.get("summary");

            Map<String, Object> properties = new HashMap<>();
            properties.put("jira_key", issue.get("key"));
            properties.put("title", summary);
            properties.put("status", status != null ? status.get("name") : null);
            properties.put("issue_type", issueType != null ? issueType.get("name") : null);
            properties.put("priority", priority != null ? priority.get("name") : null);
            properties.put("assignee", assigneeField != null ? assigneeField.get("displayName") : null);

            ActorDto actor = reporter != null
                    ? new ActorDto((String) reporter.get("accountId"), (String) reporter.get("displayName"))
                    : new ActorDto(null, null);

            events.add(new NormalizedEvent(
                    "Issue",
                    "JIRA",
                    createdAt != null ? OffsetDateTime.parse(createdAt, JIRA_DATE_FMT).toInstant() : Instant.now(),
                    actor,
                    properties,
                    refsExtractor.extract(summary)
            ));
        }
        return events;
    }
}
