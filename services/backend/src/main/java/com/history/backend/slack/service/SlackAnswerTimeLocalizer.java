package com.history.backend.slack.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// clients/web-dashboard/src/lib/remarkLocalTime.ts 규칙을 Slack 답변 본문에 그대로 재현한다.
// ai-engine은 시각을 전부 UTC ISO로 정규화해 내보내므로(docs/tools.md), 표시만 각자 뷰어의
// 현지 시간으로 옮긴다 — 웹과 Slack 두 표시 경로가 같은 시각을 다르게 보여주면 안 된다.
public final class SlackAnswerTimeLocalizer {

    // 시각·시간대가 모두 있는 완전한 ISO-8601만 매칭한다.
    // 날짜 단독(2026-03-12)은 의도적으로 제외한다 — 날짜는 instant가 아니라서 로컬로 옮기면
    // 음수 오프셋 지역에서 하루가 밀린다(UTC 자정 근처 값이 전날로 표시됨).
    private static final Pattern ISO_INSTANT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})");
    private static final Pattern ISO_INSTANT_ONLY =
            Pattern.compile("^(?:" + ISO_INSTANT.pattern() + ")$");

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                    .withLocale(Locale.KOREA);

    private SlackAnswerTimeLocalizer() {
    }

    public static String localize(String markdown, ZoneId zone) {
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
                result.append(line);
            } else {
                result.append(convertLine(line, zone));
            }
            if (i < lines.length - 1) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    // 인라인 코드(백틱)는 통째로 시각 하나일 때만 벗겨서 변환하고, 그 외엔 백틱째로 보존한다.
    // 모델이 시각을 백틱으로 감싸는 습관이 있어 전부 건너뛰면 같은 문단에서 표기가 뒤섞인다.
    private static String convertLine(String line, ZoneId zone) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        int length = line.length();
        while (i < length) {
            if (line.charAt(i) == '`') {
                int end = line.indexOf('`', i + 1);
                if (end == -1) {
                    result.append(convertPlainText(line.substring(i), zone));
                    break;
                }
                String inner = line.substring(i + 1, end).trim();
                String converted = ISO_INSTANT_ONLY.matcher(inner).matches() ? tryConvert(inner, zone) : null;
                if (converted != null) {
                    result.append(converted);
                } else {
                    // 벗길 수 없으면(시각 외 내용 혼재, 또는 파싱 실패) 백틱째로 원문 보존
                    result.append(line, i, end + 1);
                }
                i = end + 1;
            } else {
                int nextBacktick = line.indexOf('`', i);
                int segmentEnd = nextBacktick == -1 ? length : nextBacktick;
                result.append(convertPlainText(line.substring(i, segmentEnd), zone));
                i = segmentEnd;
            }
        }
        return result.toString();
    }

    private static String convertPlainText(String text, ZoneId zone) {
        Matcher matcher = ISO_INSTANT.matcher(text);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String match = matcher.group();
            String converted = tryConvert(match, zone);
            result.append(text, last, matcher.start());
            result.append(converted != null ? converted : match);
            last = matcher.end();
        }
        result.append(text.substring(last));
        return result.toString();
    }

    // 파싱 실패(정규식엔 맞지만 실제 존재하지 않는 날짜·시각)는 원문을 그대로 보존한다.
    private static String tryConvert(String iso, ZoneId zone) {
        try {
            ZonedDateTime zoned = OffsetDateTime.parse(iso).atZoneSameInstant(zone);
            return FORMATTER.format(zoned);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
