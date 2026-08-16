package com.history.pipeline_worker.source.notion;

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

/**
 * Notion page(+ 재귀 조회로 평문화한 본문) → Document {@link NormalizedEvent}.
 *
 * <p>다른 Normalizer와 달리 raw API 응답 하나만으로 완결되지 않는다 — 본문은 별도 재귀 호출
 * (블록 트리), 작성자·편집자 이름/이메일은 워크스페이스 전체 사용자 맵으로 보강해야 하므로
 * {@link NotionCollector}가 둘 다 미리 채워 넘긴다. 페이지 자체는 순수 매핑만 한다.</p>
 */
@Component
@RequiredArgsConstructor
public class NotionNormalizer {

    private final RefsExtractor refsExtractor;

    public NormalizedEvent normalizePage(
            String projectId, Map<String, Object> page, String body, Map<String, NotionRawService.NotionUser> users
    ) {
        String externalId = (String) page.get("id");
        if (externalId == null) {
            return null;
        }

        String title = extractTitle(page);
        Map<String, Object> parent = asMap(page.get("parent"));

        Map<String, Object> properties = new HashMap<>();
        properties.put("external_id", externalId);
        properties.put("title", title);
        properties.put("body", body);
        properties.put("url", page.get("url"));
        properties.put("created_at", page.get("created_time"));
        String parentType = parent.get("type") instanceof String type ? type : null;
        properties.put("parent_type", parentType);
        if ("page_id".equals(parentType)) {
            properties.put("parent_external_id", parent.get("page_id"));
        }

        Map<String, Object> refs = new HashMap<>(refsExtractor.extract(title + "\n\n" + body));
        // Notion은 last_edited_by 1명만 준다 — EDITED는 누적 관계라 refs.editors도 배열로 감싼다
        // (담당자 스냅샷과 의도적으로 다른 규약 — docs/normalized-event.md 「편집자 누적 규약」).
        ActorDto editor = resolveActor(users, partialUserId(page.get("last_edited_by")));
        if (editor.id() != null) {
            List<Map<String, Object>> editors = new ArrayList<>();
            Map<String, Object> editorEntry = new HashMap<>();
            editorEntry.put("id", editor.id());
            editorEntry.put("name", editor.name());
            editorEntry.put("email", editor.email());
            editorEntry.put("bot", editor.bot());
            editors.add(editorEntry);
            refs.put("editors", editors);
        }

        return new NormalizedEvent(
                projectId,
                "Document",
                "NOTION",
                resolveOccurredAt(page.get("last_edited_time")),
                resolveActor(users, partialUserId(page.get("created_by"))),
                properties,
                refs
        );
    }

    // title property는 표준 페이지는 "title"이라는 키를 쓰지만, database 안 page는 팀이 임의로
    // 이름 붙인 키(예: "Name")를 쓸 수 있다 — 값이 아니라 type=="title"로 찾는다.
    @SuppressWarnings("unchecked")
    private static String extractTitle(Map<String, Object> page) {
        Map<String, Object> properties = asMap(page.get("properties"));
        for (Object propertyValue : properties.values()) {
            Map<String, Object> property = asMap(propertyValue);
            if (!"title".equals(property.get("type"))) {
                continue;
            }
            if (property.get("title") instanceof List<?> richText) {
                return NotionBlockFlattener.plainTextOf((List<Object>) richText);
            }
        }
        return "";
    }

    private static String partialUserId(Object partialUser) {
        Map<String, Object> map = asMap(partialUser);
        return map.get("id") instanceof String id ? id : null;
    }

    // GET /v1/users 전량 캐시로 이름·이메일·bot 여부를 보강한다(§8) — created_by/last_edited_by는
    // partial user({object, id}뿐)라 이 보강 없이는 모든 Actor 이름이 조용히 null이 된다.
    private static ActorDto resolveActor(Map<String, NotionRawService.NotionUser> users, String userId) {
        if (userId == null) {
            return new ActorDto(null, null, null, null);
        }
        NotionRawService.NotionUser user = users.get(userId);
        if (user == null) {
            return new ActorDto(userId, null, null, null);
        }
        return new ActorDto(userId, user.name(), user.email(), user.bot());
    }

    private static Instant resolveOccurredAt(Object lastEditedTime) {
        return lastEditedTime instanceof String text ? Instant.parse(text) : Instant.now();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
