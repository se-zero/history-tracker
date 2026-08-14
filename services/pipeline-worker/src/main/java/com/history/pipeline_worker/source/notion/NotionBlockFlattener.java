package com.history.pipeline_worker.source.notion;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Notion 블록 하나를 마크다운 유사 평문 한 줄(또는 여러 줄)로 접는 순수 함수. 재귀(자식 블록 조회)는
 * 하지 않는다 — 트리 순회와 페이지네이션은 네트워크가 필요해 {@link NotionRawService}가 소유하고,
 * 이 클래스는 블록 하나의 렌더링 규칙만 고정한다(docs/notion-integration.md §2-2).
 *
 * <p>ai-engine이 Notion 블록 구조를 몰라도 되도록, pipeline-worker가 여기서 접어 평문
 * {@code properties.body}로 보낸다 — 계약이 소스 중립으로 남는 이유다.</p>
 */
final class NotionBlockFlattener {

    // 재귀하지 않는 타입 — 하위 페이지·데이터베이스는 각자 별도 Document(child_page)이거나
    // 노드로 만들지 않는 컨테이너(child_database)다. 재귀하면 같은 본문이 부모·자식에 중복
    // 저장되고 임베딩 비용이 배가된다.
    static final Set<String> NON_RECURSING_TYPES = Set.of("child_page", "child_database");

    private static final Set<String> CAPTION_ONLY_TYPES =
            Set.of("image", "file", "embed", "video", "pdf", "bookmark");

    private NotionBlockFlattener() {
    }

    static String render(Map<String, Object> block) {
        Object typeValue = block.get("type");
        if (!(typeValue instanceof String type)) {
            return "";
        }
        return switch (type) {
            case "heading_1" -> "# " + richText(block, type);
            case "heading_2" -> "## " + richText(block, type);
            case "heading_3" -> "### " + richText(block, type);
            case "paragraph", "quote", "callout" -> richText(block, type);
            case "bulleted_list_item", "numbered_list_item" -> "- " + richText(block, type);
            case "to_do" -> (isChecked(block, type) ? "- [x] " : "- [ ] ") + richText(block, type);
            case "code" -> renderCode(block);
            case "table_row" -> renderTableRow(block);
            case "child_page" -> title(block, "child_page");
            case "child_database" -> title(block, "child_database");
            default -> CAPTION_ONLY_TYPES.contains(type) ? caption(block, type) : "";
        };
    }

    private static String renderCode(Map<String, Object> block) {
        Map<String, Object> typed = typed(block, "code");
        Object language = typed.get("language");
        String fence = "```" + (language instanceof String lang ? lang : "");
        return fence + "\n" + richTextOf(typed) + "\n```";
    }

    // table_row.cells는 셀별 rich_text 배열의 배열이다 — {@code [[rich_text...], [rich_text...]]}.
    @SuppressWarnings("unchecked")
    private static String renderTableRow(Map<String, Object> block) {
        Map<String, Object> typed = typed(block, "table_row");
        Object cellsValue = typed.get("cells");
        if (!(cellsValue instanceof List<?> cells)) {
            return "";
        }
        return cells.stream()
                .map(cell -> cell instanceof List<?> richText ? plainTextOf((List<Object>) richText) : "")
                .collect(Collectors.joining(" | "));
    }

    private static boolean isChecked(Map<String, Object> block, String type) {
        return Boolean.TRUE.equals(typed(block, type).get("checked"));
    }

    private static String title(Map<String, Object> block, String type) {
        Object titleValue = typed(block, type).get("title");
        return titleValue instanceof String title ? title : "";
    }

    private static String richText(Map<String, Object> block, String type) {
        return richTextOf(typed(block, type));
    }

    @SuppressWarnings("unchecked")
    private static String richTextOf(Map<String, Object> typed) {
        Object richTextValue = typed.get("rich_text");
        return richTextValue instanceof List<?> richText ? plainTextOf((List<Object>) richText) : "";
    }

    // image·file·embed·video·pdf·bookmark는 본문이 rich_text가 아니라 caption 키에 담긴다 —
    // richTextOf를 그대로 쓰면 이 타입들은 항상 빈 문자열이 된다(캡션이 있어도 조용히 유실).
    @SuppressWarnings("unchecked")
    private static String caption(Map<String, Object> block, String type) {
        Object captionValue = typed(block, type).get("caption");
        return captionValue instanceof List<?> caption ? plainTextOf((List<Object>) caption) : "";
    }

    // 각 rich_text 원소의 plain_text만 이어붙인다(annotation·색은 버린다 — §2-2).
    @SuppressWarnings("unchecked")
    private static String plainTextOf(List<Object> richText) {
        StringBuilder sb = new StringBuilder();
        for (Object element : richText) {
            if (element instanceof Map<?, ?> map) {
                Object plainText = ((Map<String, Object>) map).get("plain_text");
                if (plainText instanceof String text) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> typed(Map<String, Object> block, String type) {
        Object value = block.get(type);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
