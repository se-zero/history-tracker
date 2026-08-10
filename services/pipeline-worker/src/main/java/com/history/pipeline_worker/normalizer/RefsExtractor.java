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

    // Asana 태스크 URL 2계열 — V0(무기한 지원): /0/{project_gid}/{task_gid},
    // V1: /1/{workspace_gid}(/project/{project_gid})?/task/{task_gid}
    private static final Pattern ASANA_TASK_URL_V0 =
            Pattern.compile("https?://app\\.asana\\.com/0/\\d+/(\\d+)");
    private static final Pattern ASANA_TASK_URL_V1 =
            Pattern.compile("https?://app\\.asana\\.com/1/\\d+(?:/project/\\d+)?/task/(\\d+)");

    /**
     * 텍스트에서 이슈 키와 PR 번호를 추출한다.
     *
     * 반환 키:
     *   - "issueKey":  첫 번째 매치 (String) — 기존 컨슈머 호환용
     *   - "issueKeys": 중복 제거된 전체 매치 목록 (List&lt;String&gt;) — PR text TRIGGERED_BY 전파용
     *   - "prNumber": 첫 번째 매치 (String)
     *   - "issueExternalRefs": Asana 태스크 URL의 gid 목록 (List&lt;Map&lt;String,Object&gt;&gt;,
     *     각 원소 {source, externalId}) — Asana는 이슈 키 체계가 없어 URL에서만 식별된다.
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

        // Asana는 이슈 키(ABC-123) 체계가 없어 URL의 gid로만 참조를 식별한다.
        // source를 리터럴 "ASANA"로 고정하는 이유: 매치가 app.asana.com URL 패턴에서만 나오므로
        // 도메인이 소스를 이미 내재하고 있어 크로스 소스 오연결이 구조적으로 차단된다.
        // 패턴이 2개뿐이라 provider별 레지스트리 구조는 아직 만들지 않는다(다음 URL 소스 추가 시 일반화).
        Set<String> asanaTaskGids = new LinkedHashSet<>();
        Matcher asanaV0Matcher = ASANA_TASK_URL_V0.matcher(text);
        while (asanaV0Matcher.find()) {
            asanaTaskGids.add(asanaV0Matcher.group(1));
        }
        Matcher asanaV1Matcher = ASANA_TASK_URL_V1.matcher(text);
        while (asanaV1Matcher.find()) {
            asanaTaskGids.add(asanaV1Matcher.group(1));
        }
        if (!asanaTaskGids.isEmpty()) {
            List<Map<String, Object>> issueExternalRefs = new ArrayList<>();
            for (String gid : asanaTaskGids) {
                issueExternalRefs.add(Map.of("source", "ASANA", "externalId", gid));
            }
            refs.put("issueExternalRefs", issueExternalRefs);
        }

        return refs;
    }
}
