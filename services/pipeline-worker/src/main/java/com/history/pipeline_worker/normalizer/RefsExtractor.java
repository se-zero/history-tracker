package com.history.pipeline_worker.normalizer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 텍스트에서 다른 시스템의 ID를 정규식으로 추출
@Component
public class RefsExtractor {

    // 이슈 트래커 키 — Jira·Linear 등이 공유하는 ABC-123 형식
    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z]{2,}-\\d+)\\b");

    // PR 번호
    private static final Pattern PR_NUMBER = Pattern.compile("PR\\s*#(\\d+)", Pattern.CASE_INSENSITIVE);

    // 이슈 키(ABC-123) 체계가 없는 소스는 태스크 URL에서만 참조를 식별한다. source를 패턴별
    // 리터럴로 고정하는 이유: 매치가 해당 소스의 URL 도메인에서만 나오므로 도메인이 소스를 이미
    // 내재하고 있어 크로스 소스 오연결이 구조적으로 차단된다.
    private record UrlRefPattern(Pattern pattern, String source) {
    }

    // Asana 태스크 URL 2계열 — V0(무기한 지원): /0/{project_gid}/{task_gid},
    // V1: /1/{workspace_gid}(/project/{project_gid})?/task/{task_gid}
    // ClickUp 공유 태스크 URL: /t/{task_id}. Custom Task ID URL(/t/{team_id}/{CUSTOM-123})에서
    // 숫자 team_id를 task id로 오인하지 않도록 \b가 부분 백트래킹 캡처(예: 1234567에서 123456만
    // 잡는 것)를 막고, (?!/)가 두-세그먼트 custom 형식을 통째로 거부한다 — 그 안의 CUSTOM-123은
    // 기존 ISSUE_KEY 패턴이 잡는다(linear.app URL 경로의 identifier를 잡는 것과 같은 전례).
    private static final List<UrlRefPattern> URL_REF_PATTERNS = List.of(
            new UrlRefPattern(Pattern.compile("https?://app\\.asana\\.com/0/\\d+/(\\d+)"), "ASANA"),
            new UrlRefPattern(Pattern.compile("https?://app\\.asana\\.com/1/\\d+(?:/project/\\d+)?/task/(\\d+)"), "ASANA"),
            new UrlRefPattern(Pattern.compile("https?://app\\.clickup\\.com/t/([a-z0-9]+)\\b(?!/)"), "CLICKUP")
    );

    /**
     * 텍스트에서 이슈 키와 PR 번호를 추출한다.
     *
     * 반환 키:
     *   - "issueKey":  첫 번째 매치 (String) — 기존 컨슈머 호환용
     *   - "issueKeys": 중복 제거된 전체 매치 목록 (List&lt;String&gt;) — PR text TRIGGERED_BY 전파용
     *   - "prNumber": 첫 번째 매치 (String)
     *   - "issueExternalRefs": 이슈 키 체계가 없는 소스(Asana·ClickUp)의 태스크 URL 참조 목록
     *     (List&lt;Map&lt;String,Object&gt;&gt;, 각 원소 {source, externalId})
     *
     * issueKey/issueKeys/issueExternalRefs는 매치가 1건 이상일 때만 포함된다.
     * 반환 Map은 String/List 값을 함께 담기 위해 Map&lt;String, Object&gt;로 선언한다.
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

        // URL_REF_PATTERNS를 순회하며 (source, externalId) 쌍을 LinkedHashSet으로 중복 제거한다.
        Set<Map<String, Object>> externalRefs = new LinkedHashSet<>();
        for (UrlRefPattern urlRefPattern : URL_REF_PATTERNS) {
            Matcher urlMatcher = urlRefPattern.pattern().matcher(text);
            while (urlMatcher.find()) {
                externalRefs.add(Map.of("source", urlRefPattern.source(), "externalId", urlMatcher.group(1)));
            }
        }
        if (!externalRefs.isEmpty()) {
            refs.put("issueExternalRefs", new ArrayList<>(externalRefs));
        }

        return refs;
    }
}
