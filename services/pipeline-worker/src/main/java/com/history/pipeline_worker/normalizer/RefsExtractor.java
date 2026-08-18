package com.history.pipeline_worker.normalizer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 텍스트에서 다른 시스템의 ID를 정규식으로 추출
@Component
public class RefsExtractor {

    // 이슈 트래커 키 — Jira·Linear 등이 공유하는 ABC-123 형식
    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z]{2,}-\\d+)\\b");

    // PR 번호
    private static final Pattern PR_NUMBER = Pattern.compile("PR\\s*#(\\d+)", Pattern.CASE_INSENSITIVE);

    // URL 기반 참조 레지스트리 — 이슈 키(ABC-123) 체계가 없는 소스(Asana·ClickUp)의 태스크 URL과
    // 문서 소스(Notion)의 페이지 URL을 함께 담는다. source를 패턴별 리터럴로 고정하는 이유: 매치가
    // 해당 소스의 URL 도메인에서만 나오므로 도메인이 소스를 이미 내재하고 있어 크로스 소스 오연결이
    // 구조적으로 차단된다. refsKey로 출력 맵의 키(issueExternalRefs vs documentExternalRefs)를
    // 갈라, 같은 "SOURCE:externalId" 실키 pre-node 메커니즘을 이슈 외 아키타입에도 재사용한다
    // (docs/integration-abstraction.md §3-1이 미뤄 둔 레지스트리화 — Notion이 다음 URL 기반 소스다).
    private record UrlRefPattern(Pattern pattern, String source, String refsKey, UnaryOperator<String> idTransform) {
        UrlRefPattern(Pattern pattern, String source, String refsKey) {
            this(pattern, source, refsKey, UnaryOperator.identity());
        }
    }

    private static final List<UrlRefPattern> URL_REF_PATTERNS = List.of(
            // Asana 태스크 URL 2계열 — V0(무기한 지원): /0/{project_gid}/{task_gid},
            // V1: /1/{workspace_gid}(/project/{project_gid})?/task/{task_gid}
            new UrlRefPattern(
                    Pattern.compile("https?://app\\.asana\\.com/0/\\d+/(\\d+)"), "ASANA", "issueExternalRefs"),
            new UrlRefPattern(
                    Pattern.compile("https?://app\\.asana\\.com/1/\\d+(?:/project/\\d+)?/task/(\\d+)"),
                    "ASANA", "issueExternalRefs"),
            // ClickUp 공유 태스크 URL: /t/{task_id}. Custom Task ID URL(/t/{team_id}/{CUSTOM-123})에서
            // 숫자 team_id를 task id로 오인하지 않도록 \b가 부분 백트래킹 캡처를 막고, (?!/)가
            // 두-세그먼트 custom 형식을 통째로 거부한다 — 그 안의 CUSTOM-123은 기존 ISSUE_KEY 패턴이
            // 잡는다(linear.app URL 경로의 identifier를 잡는 것과 같은 전례).
            new UrlRefPattern(
                    Pattern.compile("https?://app\\.clickup\\.com/t/([a-z0-9]+)\\b(?!/)"), "CLICKUP", "issueExternalRefs"),
            // Notion 페이지 URL — https://www.notion.so/{제목-슬러그}-{32자리 hex} 또는
            // https://www.notion.so/{32자리 hex}, 워크스페이스 세그먼트·쿼리스트링이 붙기도 한다.
            // API가 돌려주는 page id는 하이픈이 있는 UUID지만 URL에는 하이픈이 없으므로, 캡처한
            // 32자리 hex를 idTransform(toHyphenatedUuid)으로 정규화해 Document.external_id와
            // 맞춘다 — 안 맞추면 링크가 조용히 0건이 된다.
            new UrlRefPattern(
                    Pattern.compile("https?://(?:www\\.)?notion\\.so/\\S*?(?<![a-f0-9])([a-f0-9]{32})(?![a-f0-9])",
                            Pattern.CASE_INSENSITIVE),
                    "NOTION", "documentExternalRefs", RefsExtractor::toHyphenatedUuid)
    );

    /**
     * 텍스트에서 이슈 키·PR 번호·URL 기반 참조를 추출한다.
     *
     * 반환 키:
     *   - "issueKey":  첫 번째 이슈 키 매치 (String) — 기존 컨슈머 호환용
     *   - "issueKeys": 중복 제거된 전체 이슈 키 매치 목록 (List&lt;String&gt;) — PR text TRIGGERED_BY 전파용
     *   - "prNumber": 첫 번째 매치 (String)
     *   - "issueExternalRefs": 이슈 키 체계가 없는 소스(Asana·ClickUp)의 태스크 URL 참조 목록
     *     (List&lt;Map&lt;String,Object&gt;&gt;, 각 원소 {source, externalId})
     *   - "documentExternalRefs": 문서 소스(Notion)의 페이지 URL 참조 목록 — 형태는 issueExternalRefs와 동일
     *
     * 각 키는 매치가 1건 이상일 때만 포함된다. 반환 Map은 String/List 값을 함께 담기 위해
     * Map&lt;String, Object&gt;로 선언한다.
     */
    public Map<String, Object> extract(String text) {
        Map<String, Object> refs = new HashMap<>();
        if (text == null || text.isBlank()) return refs;

        List<String> issueKeys = new ArrayList<>();
        Matcher issueMatcher = ISSUE_KEY.matcher(text);
        while (issueMatcher.find()) {
            String key = issueMatcher.group(1);
            if (!issueKeys.contains(key)) {
                issueKeys.add(key);
            }
        }
        if (!issueKeys.isEmpty()) {
            // 단일: 첫 매치만 (기존 호환).
            refs.put("issueKey", issueKeys.get(0));
            // 전체: PR 제목/본문에서 여러 키를 동시에 참조하는 케이스를 위해 별도 보존.
            refs.put("issueKeys", issueKeys);
        }

        Matcher prMatcher = PR_NUMBER.matcher(text);
        if (prMatcher.find()) {
            refs.put("prNumber", prMatcher.group(1));
        }

        // URL_REF_PATTERNS를 순회하며 refsKey별로 (source, externalId) 쌍을 LinkedHashSet으로
        // 중복 제거한다. LinkedHashMap/Set을 써서 등장 순서를 보존한다(테스트·로그 안정성).
        Map<String, Set<Map<String, Object>>> externalRefsByKey = new LinkedHashMap<>();
        for (UrlRefPattern urlRefPattern : URL_REF_PATTERNS) {
            Matcher urlMatcher = urlRefPattern.pattern().matcher(text);
            while (urlMatcher.find()) {
                String externalId = urlRefPattern.idTransform().apply(urlMatcher.group(1));
                externalRefsByKey
                        .computeIfAbsent(urlRefPattern.refsKey(), key -> new LinkedHashSet<>())
                        .add(Map.of("source", urlRefPattern.source(), "externalId", externalId));
            }
        }
        for (Map.Entry<String, Set<Map<String, Object>>> entry : externalRefsByKey.entrySet()) {
            refs.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }

        return refs;
    }

    // 32자리 hex(하이픈 없음) → 8-4-4-4-12 하이픈 UUID로 정규화. 소문자로 통일한다(Notion API가
    // 내려주는 page id도 소문자다).
    private static String toHyphenatedUuid(String hex32) {
        String lower = hex32.toLowerCase(Locale.ROOT);
        return lower.substring(0, 8) + "-" + lower.substring(8, 12) + "-" + lower.substring(12, 16)
                + "-" + lower.substring(16, 20) + "-" + lower.substring(20);
    }
}
