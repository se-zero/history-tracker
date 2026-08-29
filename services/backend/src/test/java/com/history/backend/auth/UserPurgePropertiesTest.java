package com.history.backend.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserPurgeProperties: 퍼지 설정 유효성 검사")
class UserPurgePropertiesTest {

    @Test
    @DisplayName("batch-size가 0 이하면 IllegalArgumentException 발생")
    void rejectsInvalidBatchSize() {
        assertThatThrownBy(() -> new UserPurgeProperties(true, Duration.ofDays(30), "0 0 3 * * *", 0, Duration.ofDays(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user-lifecycle.purge.batch-size must be positive.");
    }

    @Test
    @DisplayName("grace-period가 0이면 IllegalArgumentException 발생")
    void rejectsInvalidGracePeriod() {
        assertThatThrownBy(() -> new UserPurgeProperties(true, Duration.ZERO, "0 0 3 * * *", 100, Duration.ofDays(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user-lifecycle.purge.grace-period must be positive.");
    }

    @Test
    @DisplayName("force-purge-after가 0이면 IllegalArgumentException 발생")
    void rejectsInvalidForcePurgeAfterWhenZero() {
        assertThatThrownBy(() -> new UserPurgeProperties(true, Duration.ofDays(30), "0 0 3 * * *", 100, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user-lifecycle.purge.force-purge-after must be positive.");
    }

    @Test
    @DisplayName("force-purge-after가 음수면 IllegalArgumentException 발생")
    void rejectsInvalidForcePurgeAfterWhenNegative() {
        assertThatThrownBy(() -> new UserPurgeProperties(true, Duration.ofDays(30), "0 0 3 * * *", 100, Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user-lifecycle.purge.force-purge-after must be positive.");
    }
}
