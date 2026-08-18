package com.history.pipeline_worker.source.notion;

import com.history.pipeline_worker.dto.NormalizedEvent;
import com.history.pipeline_worker.normalizer.RefsExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotionNormalizer: Notion page(+평문화된 본문) → Document NormalizedEvent")
class NotionNormalizerTest {

    private final NotionNormalizer normalizer = new NotionNormalizer(new RefsExtractor());

    @Test
    @DisplayName("기본 필드 매핑 — external_id·title·body·url·created_at·occurredAt(last_edited_time)")
    void normalizePage_mapsBasicFields() {
        Map<String, Object> page = Map.of(
                "id", "page-1",
                "url", "https://www.notion.so/Auth-page-1",
                "created_time", "2026-08-01T00:00:00.000Z",
                "last_edited_time", "2026-08-10T00:00:00.000Z",
                "parent", Map.of("type", "workspace", "workspace", true),
                "properties", titleProperty("인증 설계")
        );

        NormalizedEvent event = normalizer.normalizePage("p1", page, "본문입니다", Map.of());

        assertThat(event.nodeType()).isEqualTo("Document");
        assertThat(event.source()).isEqualTo("NOTION");
        assertThat(event.projectId()).isEqualTo("p1");
        assertThat(event.occurredAt().toString()).isEqualTo("2026-08-10T00:00:00Z");
        assertThat(event.properties())
                .containsEntry("external_id", "page-1")
                .containsEntry("title", "인증 설계")
                .containsEntry("body", "본문입니다")
                .containsEntry("url", "https://www.notion.so/Auth-page-1")
                .containsEntry("created_at", "2026-08-01T00:00:00.000Z")
                .containsEntry("parent_type", "workspace");
        assertThat(event.properties()).doesNotContainKey("parent_external_id");
    }

    @Test
    @DisplayName("external_id(page.id)가 없으면 이벤트를 만들지 않는다 — 불변 ID 없이는 재수집 멱등성이 없다")
    void normalizePage_missingId_returnsNull() {
        Map<String, Object> page = Map.of("properties", titleProperty("제목"));

        assertThat(normalizer.normalizePage("p1", page, "", Map.of())).isNull();
    }

    @Test
    @DisplayName("parent.type이 page_id일 때만 parent_external_id를 채운다 — CHILD_OF 매칭 키")
    void normalizePage_pageParent_fillsParentExternalId() {
        Map<String, Object> page = Map.of(
                "id", "page-1",
                "last_edited_time", "2026-08-10T00:00:00.000Z",
                "parent", Map.of("type", "page_id", "page_id", "parent-page-1"),
                "properties", titleProperty("하위 문서")
        );

        NormalizedEvent event = normalizer.normalizePage("p1", page, "", Map.of());

        assertThat(event.properties())
                .containsEntry("parent_type", "page_id")
                .containsEntry("parent_external_id", "parent-page-1");
    }

    @Test
    @DisplayName("title property는 키 이름이 아니라 type==title로 찾는다 — database 안 page는 커스텀 키(예: Name)를 쓴다")
    void normalizePage_titlePropertyFoundByTypeNotKeyName() {
        Map<String, Object> page = Map.of(
                "id", "page-1",
                "last_edited_time", "2026-08-10T00:00:00.000Z",
                "parent", Map.of("type", "workspace", "workspace", true),
                "properties", Map.of(
                        "Status", Map.of("type", "select", "select", Map.of("name", "Done")),
                        "Name", Map.of("type", "title", "title", List.of(Map.of("plain_text", "커스텀 제목")))
                )
        );

        NormalizedEvent event = normalizer.normalizePage("p1", page, "", Map.of());

        assertThat(event.properties()).containsEntry("title", "커스텀 제목");
    }

    @Test
    @DisplayName("created_by/last_edited_by는 partial user(id만)라 사용자 맵으로 이름·이메일·bot을 보강한다")
    void normalizePage_resolvesActorAndEditorFromUserMap() {
        Map<String, Object> page = Map.of(
                "id", "page-1",
                "last_edited_time", "2026-08-10T00:00:00.000Z",
                "created_by", Map.of("object", "user", "id", "u-author"),
                "last_edited_by", Map.of("object", "user", "id", "u-editor"),
                "parent", Map.of("type", "workspace", "workspace", true),
                "properties", titleProperty("문서")
        );
        Map<String, NotionRawService.NotionUser> users = Map.of(
                "u-author", new NotionRawService.NotionUser("Author", "author@example.com", false),
                "u-editor", new NotionRawService.NotionUser("Editor", "editor@example.com", false)
        );

        NormalizedEvent event = normalizer.normalizePage("p1", page, "", users);

        assertThat(event.actor().id()).isEqualTo("u-author");
        assertThat(event.actor().name()).isEqualTo("Author");
        assertThat(event.actor().email()).isEqualTo("author@example.com");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> editors = (List<Map<String, Object>>) event.refs().get("editors");
        assertThat(editors).hasSize(1);
        assertThat(editors.get(0))
                .containsEntry("id", "u-editor")
                .containsEntry("name", "Editor")
                .containsEntry("email", "editor@example.com")
                .containsEntry("bot", false);
    }

    @Test
    @DisplayName("사용자 맵에 없는 id(게스트·삭제된 사용자 등)는 id만 남고 이름·이메일은 null이다")
    void normalizePage_userNotInMap_idOnlyWithNullNameAndEmail() {
        Map<String, Object> page = Map.of(
                "id", "page-1",
                "last_edited_time", "2026-08-10T00:00:00.000Z",
                "created_by", Map.of("object", "user", "id", "u-unknown"),
                "parent", Map.of("type", "workspace", "workspace", true),
                "properties", titleProperty("문서")
        );

        NormalizedEvent event = normalizer.normalizePage("p1", page, "", Map.of());

        assertThat(event.actor().id()).isEqualTo("u-unknown");
        assertThat(event.actor().name()).isNull();
        assertThat(event.actor().email()).isNull();
    }

    @Test
    @DisplayName("last_edited_by가 없으면 refs.editors 키 자체가 없다")
    void normalizePage_noLastEditedBy_noEditorsKey() {
        Map<String, Object> page = Map.of(
                "id", "page-1",
                "last_edited_time", "2026-08-10T00:00:00.000Z",
                "parent", Map.of("type", "workspace", "workspace", true),
                "properties", titleProperty("문서")
        );

        NormalizedEvent event = normalizer.normalizePage("p1", page, "", Map.of());

        assertThat(event.refs()).doesNotContainKey("editors");
    }

    @Test
    @DisplayName("제목·본문에서 실제 RefsExtractor로 이슈 키·URL 참조를 추출한다")
    void normalizePage_extractsRefsFromTitleAndBody() {
        Map<String, Object> page = Map.of(
                "id", "page-1",
                "last_edited_time", "2026-08-10T00:00:00.000Z",
                "parent", Map.of("type", "workspace", "workspace", true),
                "properties", titleProperty("HT-7 설계 문서")
        );

        NormalizedEvent event = normalizer.normalizePage(
                "p1", page, "관련 태스크: https://app.asana.com/0/123/456", Map.of());

        assertThat(event.refs()).containsEntry("issueKey", "HT-7");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issueExternalRefs = (List<Map<String, Object>>) event.refs().get("issueExternalRefs");
        assertThat(issueExternalRefs).containsExactly(Map.of("source", "ASANA", "externalId", "456"));
    }

    private static Map<String, Object> titleProperty(String plainText) {
        return Map.of("title", Map.of("type", "title", "title", List.of(Map.of("plain_text", plainText))));
    }
}
