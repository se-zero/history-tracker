package com.history.pipeline_worker.source.clickup;

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
public class ClickUpNormalizer {

    private final RefsExtractor refsExtractor;

    // ClickUpRawService.ClickUpTaskPage.body()({"tasks": [태스크...]}) → Issue 이벤트 목록.
    // ClickUp은 이슈 키(custom_id)·status 객체(status/type)·priority가 있어 Asana와 달리 이 키들을
    // 조건부로 채우고, 날짜는 epoch ms 문자열이라 ISO-8601로 변환해서 발행한다.
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeTasks(String projectId, Map<String, Object> responseBody) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (responseBody == null) return events;

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) responseBody.get("tasks");
        if (tasks == null) return events;

        for (Map<String, Object> task : tasks) {
            // external_id는 이벤트 식별의 필수값이라, 없는 태스크는 건너뛴다.
            String id = (String) task.get("id");
            if (id == null) continue;

            Map<String, Object> status = (Map<String, Object>) task.get("status");
            Map<String, Object> creator = (Map<String, Object>) task.get("creator");
            List<Map<String, Object>> assignees = (List<Map<String, Object>>) task.get("assignees");
            Object parent = task.get("parent");
            Map<String, Object> priority = (Map<String, Object>) task.get("priority");
            String textContent = (String) task.get("text_content");

            String statusCategory = resolveStatusCategory(status);

            Map<String, Object> properties = new HashMap<>();
            properties.put("external_id", id);
            if (task.get("custom_id") instanceof String customId) {
                properties.put("issue_key", customId);
            }
            properties.put("title", task.get("name"));
            properties.put("body", textContent);
            if (status != null) {
                properties.put("status", status.get("status"));
            }
            properties.put("status_category", statusCategory);
            properties.put("created_at", isoString(epochMsToInstant(task.get("date_created"))));
            // closed일 때만 date_closed(우선)/date_done을 closed_at으로 채운다 — 재오픈된 태스크에
            // 남은 과거 date_done 값이 status_category=open인데도 closed_at으로 새 나가지 않게 한다.
            if ("closed".equals(statusCategory)) {
                Object closedAtSource = task.get("date_closed") != null ? task.get("date_closed") : task.get("date_done");
                properties.put("closed_at", isoString(epochMsToInstant(closedAtSource)));
            }
            if (priority != null && priority.get("priority") != null) {
                properties.put("priority", priority.get("priority"));
            }

            events.add(new NormalizedEvent(
                    projectId,
                    "Issue",
                    "CLICKUP",
                    epochMsToInstant(task.get("date_updated")),
                    resolveActor(creator),
                    properties,
                    resolveRefs(textContent, assignees, parent)
            ));
        }
        return events;
    }

    // status.type → 소스 중립 status_category. 알 수 없는 값·status 부재는 방어적으로 open.
    private String resolveStatusCategory(Map<String, Object> status) {
        if (status == null || !(status.get("type") instanceof String type)) {
            return "open";
        }
        return switch (type) {
            case "custom" -> "in_progress";
            case "done", "closed" -> "closed";
            default -> "open";
        };
    }

    // creator의 username이 "ClickBot"일 때만 봇으로 판정한다 — ClickUp에는 별도 봇 플래그가 없다.
    private ActorDto resolveActor(Map<String, Object> creator) {
        if (creator == null) {
            return new ActorDto(null, null, null, null);
        }
        String username = (String) creator.get("username");
        // id가 null이면 String.valueOf가 문자열 "null"을 만들어 진짜 null과 구분되지 않으므로
        // null일 때만 그대로 null을 전달한다.
        Object id = creator.get("id");
        return new ActorDto(
                id != null ? String.valueOf(id) : null,
                username,
                (String) creator.get("email"),
                "ClickBot".equals(username) ? Boolean.TRUE : null);
    }

    private Map<String, Object> resolveRefs(String textContent, List<Map<String, Object>> assignees, Object parent) {
        Map<String, Object> refs = new HashMap<>(refsExtractor.extract(textContent));

        // Issue는 최신 스냅샷이라 담당자 없음도 명시적 빈 배열로 나타내야 기존 ASSIGNED_TO가 해제된다.
        List<Map<String, Object>> assigneeEntries = new ArrayList<>();
        if (assignees != null) {
            for (Map<String, Object> assignee : assignees) {
                // id가 없는 항목을 String.valueOf로 변환하면 문자열 "null"이 유효한 id처럼 들어가므로
                // id가 있는 항목만 넣는다(해제 규약 유지).
                Object id = assignee.get("id");
                if (id == null) continue;
                String username = (String) assignee.get("username");
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", String.valueOf(id));
                entry.put("name", username);
                entry.put("email", assignee.get("email"));
                if ("ClickBot".equals(username)) {
                    entry.put("bot", true);
                }
                assigneeEntries.add(entry);
            }
        }
        refs.put("assignees", assigneeEntries);

        if (parent != null) {
            refs.put("parentExternalId", parent);
        }

        return refs;
    }

    private Instant epochMsToInstant(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return Instant.ofEpochMilli(Long.parseLong(text));
    }

    private String isoString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
