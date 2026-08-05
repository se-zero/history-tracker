package com.history.pipeline_worker.normalizer;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 텍스트에서 다른 시스템의 ID를 정규식으로 추출
@Component
public class RefsExtractor {

    // 이슈 트래커 키 — Jira·Linear 등이 공유하는 ABC-123 형식
    private static final Pattern ISSUE_KEY = Pattern.compile("\\b([A-Z]{2,}-\\d+)\\b");

    // PR 번호
    private static final Pattern PR_NUMBER = Pattern.compile("PR\\s*#(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * 텍스트에서 이슈 키와 PR 번호를 추출한다.
     *
     * 반환 키:
     *   - "issueKey":  첫 번째 매치 (String) — 기존 컨슈머 호환용
     *   - "issueKeys": 중복 제거된 전체 매치 목록 (List&lt;String&gt;) — PR text TRIGGERED_BY 전파용
     *   - "prNumber": 첫 번째 매치 (String)
     *
     * issueKey/issueKeys는 매치가 1건 이상일 때만 포함된다.
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

        return refs;
    }
}
