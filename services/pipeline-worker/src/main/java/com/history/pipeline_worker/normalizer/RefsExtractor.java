package com.history.pipeline_worker.normalizer;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 텍스트에서 다른 시스템의 ID를 정규식으로 추출
@Component
public class RefsExtractor {

    // Jira 이슈 키
    private static final Pattern JIRA_KEY = Pattern.compile("\\b([A-Z]{2,}-\\d+)\\b");

    // PR 번호
    private static final Pattern PR_NUMBER = Pattern.compile("PR\\s*#(\\d+)", Pattern.CASE_INSENSITIVE);

    public Map<String, String> extract(String text) {
        Map<String, String> refs = new HashMap<>();
        if (text == null || text.isBlank()) return refs;

        Matcher jiraMatcher = JIRA_KEY.matcher(text);
        if (jiraMatcher.find()) {
            // 여러 개 있을 경우 첫 번째만 (대표 참조)
            refs.put("jiraKey", jiraMatcher.group(1));
        }

        Matcher prMatcher = PR_NUMBER.matcher(text);
        if (prMatcher.find()) {
            refs.put("prNumber", prMatcher.group(1));
        }

        return refs;
    }
}
