package com.history.pipeline_worker.source.asana;

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
public class AsanaNormalizer {

    private final RefsExtractor refsExtractor;

    // AsanaRawService.AsanaTaskPage.body()({"data": [태스크...], "next_page": ...}) → Issue 이벤트 목록.
    // Asana에는 이슈 키 체계·상태 필드·우선순위 필드가 없어 issue_key/status/priority 키는 발행하지 않는다.
    @SuppressWarnings("unchecked")
    public List<NormalizedEvent> normalizeTasks(String projectId, Map<String, Object> responseBody) {
        List<NormalizedEvent> events = new ArrayList<>();
        if (responseBody == null) return events;

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) responseBody.get("data");
        if (tasks == null) return events;

        for (Map<String, Object> task : tasks) {
            // external_id(gid)는 이벤트 식별의 필수값이라, 없는 태스크는 건너뛴다.
            String gid = (String) task.get("gid");
            if (gid == null) continue;

            Map<String, Object> assignee = (Map<String, Object>) task.get("assignee");
            Map<String, Object> createdBy = (Map<String, Object>) task.get("created_by");
            Map<String, Object> parent = (Map<String, Object>) task.get("parent");

            String title = (String) task.get("name");
            String notes = (String) task.get("notes");
            String createdAt = (String) task.get("created_at");
            String modifiedAt = (String) task.get("modified_at");

            boolean completed = Boolean.TRUE.equals(task.get("completed"));
            String statusCategory = completed ? "closed" : "open";

            Map<String, Object> properties = new HashMap<>();
            properties.put("external_id", gid);
            properties.put("title", title);
            properties.put("body", notes);
            properties.put("status_category", statusCategory);
            properties.put("created_at", createdAt);
            // closed일 때만 completed_at을 closed_at으로 채운다 — 재오픈된 태스크에 남은 과거
            // completed_at 값이 status_category=open인데도 closed_at으로 새 나가지 않게 한다.
            if (completed) {
                properties.put("closed_at", (String) task.get("completed_at"));
            }

            events.add(new NormalizedEvent(
                    projectId,
                    "Issue",
                    "ASANA",
                    Instant.parse(modifiedAt),
                    resolveActor(createdBy),
                    properties,
                    resolveRefs(notes, assignee, parent)
            ));
        }
        return events;
    }

    // Asana에는 봇 판정 신호가 없어 actor.bot은 항상 null이다.
    private ActorDto resolveActor(Map<String, Object> createdBy) {
        if (createdBy == null) {
            return new ActorDto(null, null, null, null);
        }
        return new ActorDto(
                (String) createdBy.get("gid"),
                (String) createdBy.get("name"),
                (String) createdBy.get("email"),
                null);
    }

    private Map<String, Object> resolveRefs(String notes, Map<String, Object> assignee, Map<String, Object> parent) {
        Map<String, Object> refs = new HashMap<>(refsExtractor.extract(notes));

        // Issue는 최신 스냅샷이라 담당자 없음도 명시적 빈 배열로 나타내야 기존 ASSIGNED_TO가 해제된다.
        // Asana는 단일 assignee이며 bot 판정 신호가 없어 bot 키는 채우지 않는다.
        List<Map<String, Object>> assignees = new ArrayList<>();
        if (assignee != null) {
            Map<String, Object> assigneeEntry = new HashMap<>();
            assigneeEntry.put("id", assignee.get("gid"));
            assigneeEntry.put("name", assignee.get("name"));
            assigneeEntry.put("email", assignee.get("email"));
            assignees.add(assigneeEntry);
        }
        refs.put("assignees", assignees);

        // Asana에는 이슈 키 체계가 없어 parentIssueKey는 채우지 않는다.
        if (parent != null) {
            refs.put("parentExternalId", parent.get("gid"));
        }

        return refs;
    }
}
