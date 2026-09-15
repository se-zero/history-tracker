package com.history.backend.slack.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 표준 마크다운(LLM 답변)을 Slack 고유 문법(mrkdwn)으로 변환한다.
// Slack mrkdwn에는 제목·목록 문법이 없다 — 대신 굵게(*x*)·기울임(_x_)·취소선(~x~)·인라인 코드·
// 코드블록·인용(> )·링크(<url|텍스트>)만 지원한다. &, <, > 는 그 용도(HTML 태그 등)가 아닌 한
// 항상 이스케이프해야 한다 — 인라인 코드·코드블록 안이라도 예외가 아니다.
public final class SlackMrkdwnConverter {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)\\)");
    private static final Pattern BOLD_ASTERISK = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_UNDERSCORE = Pattern.compile("__(.+?)__");
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~(.+?)~~");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$");
    private static final Pattern BLOCKQUOTE_RESTORE = Pattern.compile("^(\\s*)&gt;\\s?");
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`]*`");
    // 코드 스팬을 임시로 가려둘 때 쓰는 경계 문자 — 일반 답변 텍스트에는 나타나지 않는 NUL
    // 제어문자라 실제 본문과 우연히 겹쳐 잘못 복원될 일이 없다.
    private static final char PLACEHOLDER_BOUNDARY = '\u0000';

    private SlackMrkdwnConverter() {
    }

    public static String convert(String markdown) {
        String[] lines = markdown.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 목록 항목 안에 들여쓴 펜스("  ```")도 펜스다 — 앞 공백을 무시하고 판별한다.
            if (line.stripLeading().startsWith("```")) {
                // 펜스를 여닫는 줄 자체는 변환 대상이 아니다 — 토글만 하고 원문 그대로 둔다.
                inFence = !inFence;
                result.append(line);
            } else if (inFence) {
                result.append(escape(line));
            } else {
                result.append(convertLine(line));
            }
            if (i < lines.length - 1) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private static String convertLine(String line) {
        String escaped = escape(line);
        String quoted = BLOCKQUOTE_RESTORE.matcher(escaped).replaceFirst("$1> ");

        // 인라인 코드 스팬은 제목·글머리·링크·굵게·취소선 변환 대상에서 제외한다 — 자리표시자로
        // 바꿔 보호한 뒤(제목·수평선처럼 줄 전체를 앵커로 삼는 규칙도 안전하게 통과시키기 위해),
        // 나머지 변환을 마치고 원래 스팬(이미 이스케이프된 상태)으로 복원한다.
        List<String> spans = new ArrayList<>();
        Matcher spanMatcher = CODE_SPAN.matcher(quoted);
        StringBuilder withPlaceholders = new StringBuilder();
        int last = 0;
        while (spanMatcher.find()) {
            withPlaceholders.append(quoted, last, spanMatcher.start());
            withPlaceholders.append(PLACEHOLDER_BOUNDARY).append(spans.size()).append(PLACEHOLDER_BOUNDARY);
            spans.add(spanMatcher.group());
            last = spanMatcher.end();
        }
        withPlaceholders.append(quoted.substring(last));

        String converted = convertOutsideSpans(withPlaceholders.toString());
        for (int i = 0; i < spans.size(); i++) {
            converted = converted.replace(
                    "" + PLACEHOLDER_BOUNDARY + i + PLACEHOLDER_BOUNDARY, spans.get(i));
        }
        return converted;
    }

    private static String convertOutsideSpans(String line) {
        String result = HEADING.matcher(line).replaceFirst("*$1*");
        result = BULLET.matcher(result).replaceFirst("$1• ");
        result = LINK.matcher(result).replaceAll("<$2|$1>");
        result = BOLD_ASTERISK.matcher(result).replaceAll("*$1*");
        result = BOLD_UNDERSCORE.matcher(result).replaceAll("*$1*");
        result = STRIKETHROUGH.matcher(result).replaceAll("~$1~");
        result = HORIZONTAL_RULE.matcher(result).replaceFirst("");
        return result;
    }

    // Slack mrkdwn 이스케이프 규칙 — &를 먼저 바꾸지 않으면 <, > 치환 결과의 &까지 다시 이스케이프된다.
    // 답변 본문뿐 아니라 ack의 질문 인용(SlackCommandsService)도 이 한 곳을 쓴다.
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
