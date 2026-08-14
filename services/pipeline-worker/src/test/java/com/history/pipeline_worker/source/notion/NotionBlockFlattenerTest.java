package com.history.pipeline_worker.source.notion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotionBlockFlattener: 블록 하나 → 마크다운 유사 평문 한 줄")
class NotionBlockFlattenerTest {

    @Test
    void heading1_prefixedWithSingleHash() {
        Map<String, Object> block = blockOf("heading_1", "rich_text", richText("인증"));
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("# 인증");
    }

    @Test
    void heading2_prefixedWithDoubleHash() {
        Map<String, Object> block = blockOf("heading_2", "rich_text", richText("토큰 갱신"));
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("## 토큰 갱신");
    }

    @Test
    void paragraph_rendersPlainText() {
        Map<String, Object> block = blockOf("paragraph", "rich_text", richText("본문입니다"));
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("본문입니다");
    }

    @Test
    void bulletedListItem_prefixedWithDash() {
        Map<String, Object> block = blockOf("bulleted_list_item", "rich_text", richText("항목 1"));
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("- 항목 1");
    }

    @Test
    void checkedToDo_prefixedWithCheckedBox() {
        Map<String, Object> typed = Map.of("rich_text", richText("완료된 작업"), "checked", true);
        Map<String, Object> block = Map.of("type", "to_do", "to_do", typed);
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("- [x] 완료된 작업");
    }

    @Test
    void uncheckedToDo_prefixedWithEmptyBox() {
        Map<String, Object> typed = Map.of("rich_text", richText("남은 작업"), "checked", false);
        Map<String, Object> block = Map.of("type", "to_do", "to_do", typed);
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("- [ ] 남은 작업");
    }

    @Test
    void code_wrapsInFenceWithLanguage() {
        Map<String, Object> typed = Map.of("rich_text", richText("print(1)"), "language", "python");
        Map<String, Object> block = Map.of("type", "code", "code", typed);
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("```python\nprint(1)\n```");
    }

    @Test
    void tableRow_joinsCellsWithPipe() {
        Map<String, Object> typed = Map.of("cells", List.of(richText("A"), richText("B"), richText("C")));
        Map<String, Object> block = Map.of("type", "table_row", "table_row", typed);
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("A | B | C");
    }

    @Test
    void childPage_rendersTitleOnly() {
        Map<String, Object> typed = Map.of("title", "하위 페이지");
        Map<String, Object> block = Map.of("type", "child_page", "child_page", typed);
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("하위 페이지");
    }

    @Test
    @DisplayName("child_page·child_database는 NON_RECURSING_TYPES에 포함된다 — 재귀하면 본문 중복·임베딩 비용 배가")
    void nonRecursingTypesContainsChildPageAndDatabase() {
        assertThat(NotionBlockFlattener.NON_RECURSING_TYPES).containsExactlyInAnyOrder("child_page", "child_database");
    }

    @Test
    @DisplayName("image·file·embed 등은 rich_text가 아니라 caption 키에서 텍스트를 읽는다")
    void image_rendersCaptionNotRichText() {
        Map<String, Object> typed = Map.of("caption", richText("스크린샷 설명"), "type", "external");
        Map<String, Object> block = Map.of("type", "image", "image", typed);
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("스크린샷 설명");
    }

    @Test
    @DisplayName("caption이 없는 image는 빈 문자열 — 상위(NotionRawService)에서 그 줄 자체가 제외된다")
    void image_withoutCaption_rendersEmptyString() {
        Map<String, Object> block = Map.of("type", "image", "image", Map.of("type", "external"));
        assertThat(NotionBlockFlattener.render(block)).isEmpty();
    }

    @Test
    void unknownType_rendersEmptyString() {
        Map<String, Object> block = Map.of("type", "unsupported_future_block");
        assertThat(NotionBlockFlattener.render(block)).isEmpty();
    }

    @Test
    @DisplayName("annotation·색은 버리고 plain_text만 이어붙인다 (여러 rich_text 세그먼트 결합)")
    void multipleRichTextSegments_concatenatedByPlainTextOnly() {
        List<Object> richText = List.of(
                Map.of("plain_text", "굵게 ", "annotations", Map.of("bold", true)),
                Map.of("plain_text", "일반")
        );
        Map<String, Object> block = Map.of("type", "paragraph", "paragraph", Map.of("rich_text", richText));
        assertThat(NotionBlockFlattener.render(block)).isEqualTo("굵게 일반");
    }

    private static Map<String, Object> blockOf(String type, String richTextKey, List<Object> richText) {
        return Map.of("type", type, type, Map.of(richTextKey, richText));
    }

    private static List<Object> richText(String plainText) {
        return List.of(Map.of("plain_text", plainText));
    }
}
