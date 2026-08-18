package com.history.backend.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AtlassianPersonalDataReportProperties: 개인정보 보고 설정 유효성 검사")
class AtlassianPersonalDataReportPropertiesTest {

    @Test
    @DisplayName("정상 값이면 생성 성공")
    void constructsWithValidValues() {
        AtlassianPersonalDataReportProperties properties = new AtlassianPersonalDataReportProperties(
                true, "0 0 3 * * *", Duration.ofDays(7), "https://api.atlassian.com/report-accounts");

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.cron()).isEqualTo("0 0 3 * * *");
        assertThat(properties.cyclePeriod()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.reportUrl()).isEqualTo("https://api.atlassian.com/report-accounts");
    }

    @Test
    @DisplayName("cron이 blank면 IllegalArgumentException 발생")
    void rejectsBlankCron() {
        assertThatThrownBy(() -> new AtlassianPersonalDataReportProperties(
                true, " ", Duration.ofDays(7), "https://api.atlassian.com/report-accounts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("atlassian.personal-data-report.cron must not be blank.");
    }

    @Test
    @DisplayName("cyclePeriod가 null이면 IllegalArgumentException 발생")
    void rejectsNullCyclePeriod() {
        assertThatThrownBy(() -> new AtlassianPersonalDataReportProperties(
                true, "0 0 3 * * *", null, "https://api.atlassian.com/report-accounts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("atlassian.personal-data-report.cycle-period must be positive.");
    }

    @Test
    @DisplayName("cyclePeriod가 0이면 IllegalArgumentException 발생")
    void rejectsZeroCyclePeriod() {
        assertThatThrownBy(() -> new AtlassianPersonalDataReportProperties(
                true, "0 0 3 * * *", Duration.ZERO, "https://api.atlassian.com/report-accounts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("atlassian.personal-data-report.cycle-period must be positive.");
    }

    @Test
    @DisplayName("cyclePeriod가 음수면 IllegalArgumentException 발생")
    void rejectsNegativeCyclePeriod() {
        assertThatThrownBy(() -> new AtlassianPersonalDataReportProperties(
                true, "0 0 3 * * *", Duration.ofDays(-1), "https://api.atlassian.com/report-accounts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("atlassian.personal-data-report.cycle-period must be positive.");
    }

    @Test
    @DisplayName("reportUrl이 blank면 IllegalArgumentException 발생")
    void rejectsBlankReportUrl() {
        assertThatThrownBy(() -> new AtlassianPersonalDataReportProperties(
                true, "0 0 3 * * *", Duration.ofDays(7), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("atlassian.personal-data-report.report-url must not be blank.");
    }
}
