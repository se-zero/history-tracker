package com.history.pipeline_worker.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public final class JiraDateUtils {

    private static final DateTimeFormatter JIRA_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    private static final DateTimeFormatter JQL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private JiraDateUtils() {
    }

    public static Instant parse(String value) {
        return OffsetDateTime.parse(value, JIRA_DATE_FORMAT).toInstant();
    }

    public static Optional<Instant> tryParse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static String formatForJql(Instant value) {
        return JQL_DATE_FORMAT.format(value);
    }
}
