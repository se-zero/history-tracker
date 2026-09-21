package com.history.backend.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PlanExpiryProperties: 플랜 만료 스케줄러 설정 유효성 검사")
class PlanExpiryPropertiesTest {

    @Test
    @DisplayName("정상 값이면 생성된다")
    void createsWithValidValues() {
        assertThatCode(() -> new PlanExpiryProperties(true, "0 0 4 * * *", 100))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cron이 null이면 IllegalArgumentException 발생")
    void rejectsNullCron() {
        assertThatThrownBy(() -> new PlanExpiryProperties(true, null, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.plan.expiry.cron must not be blank.");
    }

    @Test
    @DisplayName("cron이 빈 문자열이면 IllegalArgumentException 발생")
    void rejectsEmptyCron() {
        assertThatThrownBy(() -> new PlanExpiryProperties(true, "", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.plan.expiry.cron must not be blank.");
    }

    @Test
    @DisplayName("cron이 공백만이면 IllegalArgumentException 발생")
    void rejectsBlankCron() {
        assertThatThrownBy(() -> new PlanExpiryProperties(true, "   ", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.plan.expiry.cron must not be blank.");
    }

    @Test
    @DisplayName("batchSize가 0이면 IllegalArgumentException 발생")
    void rejectsZeroBatchSize() {
        assertThatThrownBy(() -> new PlanExpiryProperties(true, "0 0 4 * * *", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.plan.expiry.batch-size must be positive.");
    }

    @Test
    @DisplayName("batchSize가 음수면 IllegalArgumentException 발생")
    void rejectsNegativeBatchSize() {
        assertThatThrownBy(() -> new PlanExpiryProperties(true, "0 0 4 * * *", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.plan.expiry.batch-size must be positive.");
    }
}
