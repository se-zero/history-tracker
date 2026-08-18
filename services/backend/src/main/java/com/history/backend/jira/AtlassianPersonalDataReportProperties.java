package com.history.backend.jira;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlassian.personal-data-report")
public record AtlassianPersonalDataReportProperties(
        boolean enabled,
        String cron,
        Duration cyclePeriod,
        String reportUrl
) {

    public AtlassianPersonalDataReportProperties {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("atlassian.personal-data-report.cron must not be blank.");
        }
        if (cyclePeriod == null || cyclePeriod.isNegative() || cyclePeriod.isZero()) {
            throw new IllegalArgumentException("atlassian.personal-data-report.cycle-period must be positive.");
        }
        if (reportUrl == null || reportUrl.isBlank()) {
            throw new IllegalArgumentException("atlassian.personal-data-report.report-url must not be blank.");
        }
    }
}
